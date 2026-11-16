package jp.dailypassxplink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static jp.dailypassxplink.MtropsRoutes.array;
import static jp.dailypassxplink.MtropsRoutes.flag;
import static jp.dailypassxplink.MtropsRoutes.label;
import static jp.dailypassxplink.MtropsRoutes.number;

final class MtropsLoops {
    private static final int MAX_CYCLES = 3;
    private static final long TIMETABLE_CACHE_MS = 10_000;
    private final MtropsRoutes mtrops;
    private Timetables cached;

    MtropsLoops(MtropsRoutes mtrops) {
        this.mtrops = mtrops;
    }

    Result read(MtropsRoutes.Network network, int transferSeconds) {
        if (!network.state().equals("ready")) return Result.unavailable(network.state());
        if (!network.scheduleReady()) return Result.unavailable("noSchedule");

        Map<StationPair, List<Leg>> legsByPair = new LinkedHashMap<>();
        for (MtropsRoutes.Pattern pattern : network.patterns()) {
            addLegs(pattern, legsByPair);
        }
        var stations = new HashSet<String>();
        for (StationPair pair : legsByPair.keySet()) {
            if (legsByPair.containsKey(pair.reverse())) stations.add(pair.from());
        }

        Timetables timetables = timetable(network.revision(), stations);
        if (timetables.departures.isEmpty()) return Result.unavailable("noSchedule");
        long now = System.currentTimeMillis();
        var results = new ArrayList<Loop>();
        Map<Leg, List<Trip>> tripsByLeg = new IdentityHashMap<>();
        Map<Leg, Ride> prices = new IdentityHashMap<>();
        for (var entry : legsByPair.entrySet()) {
            StationPair pair = entry.getKey();
            List<Leg> returnLegs = legsByPair.get(pair.reverse());
            if (returnLegs == null) continue;
            for (Leg outbound : entry.getValue()) {
                List<Trip> starts = tripsByLeg.computeIfAbsent(outbound, leg -> trips(leg, timetables, now));
                if (starts.isEmpty()) continue;
                for (Leg inbound : returnLegs) {
                    List<Trip> returns = tripsByLeg.computeIfAbsent(inbound, leg -> trips(leg, timetables, now));
                    Cycle cycle = plan(starts, returns, transferSeconds);
                    if (cycle == null) continue;
                    Ride out = prices.computeIfAbsent(outbound, leg -> ride(leg, network.fares()));
                    Ride back = prices.computeIfAbsent(inbound, leg -> ride(leg, network.fares()));
                    var depots = new ArrayList<>(timetables.depots.getOrDefault(out.routeId, Map.of()).keySet());
                    depots.retainAll(timetables.depots.getOrDefault(back.routeId, Map.of()).keySet());
                    List<String> sharedDepots = depots.stream().map(timetables.depots.getOrDefault(out.routeId, Map.of())::get)
                            .distinct().sorted().toList();
                    results.add(new Loop(pair.from(), pair.to(), outbound.from().name(), outbound.to().name(),
                            out, back, sharedDepots, cycle));
                }
            }
        }

        DailyPassFares.Data fares = network.fares();
        return new Result("ready", now, stations.size(), timetables.departures.size(), timetables.depotsAvailable,
                fares != null && fares.distanceFare(), fares == null ? Map.of() : fares.names(), results);
    }

    private static void addLegs(MtropsRoutes.Pattern pattern, Map<StationPair, List<Leg>> result) {
        List<MtropsRoutes.Station> stops = pattern.stops();
        if (stops.size() < 2 || pattern.sections().size() != stops.size() - 1) return;
        // A station visited twice by the same pattern has ambiguous departure records without a stop index.
        var ids = new HashSet<String>();
        for (var stop : stops) {
            if (stop.id().isEmpty() || !ids.add(stop.id())) return;
        }

        double knownMeters = 0;
        double knownSeconds = 0;
        for (MtropsRoutes.Section section : pattern.sections()) {
            if (section.meters() > 0 && section.expected() > 0) {
                knownMeters += section.meters();
                knownSeconds += section.expected();
            }
        }
        double secondsPerMeter = knownMeters > 0 ? knownSeconds / knownMeters : 0;
        for (int from = 0; from < stops.size() - 1; from++) {
            double seconds = 0;
            boolean measured = true;
            for (int to = from + 1; to < stops.size(); to++) {
                MtropsRoutes.Section section = pattern.sections().get(to - 1);
                if (section.meters() < 0) break;
                double time = section.expected() > 0 ? section.expected() : section.meters() * secondsPerMeter;
                // Retain candidates with unknown run times for matching same-train arrival events.
                if (!(time > 0)) seconds = Double.NaN;
                seconds += time;
                measured &= section.measured();
                double meters = stops.get(to).meters() - stops.get(from).meters();
                if (!(meters > 0)) continue;
                double dwell = Math.max(0, pattern.sections().get(from).dwell());
                double runSeconds = Double.isFinite(seconds) ? Math.max(1, seconds - dwell) : -1;
                Leg leg = new Leg(pattern, from, to, meters, runSeconds, measured);
                result.computeIfAbsent(new StationPair(leg.from().id(), leg.to().id()), k -> new ArrayList<>()).add(leg);
            }
        }
    }

    private synchronized Timetables timetable(long revision, HashSet<String> stations) {
        if (cached != null && cached.revision == revision && cached.requested.equals(stations)
                && System.currentTimeMillis() - cached.loadedAt < TIMETABLE_CACHE_MS) return cached;
        Map<String, CompletableFuture<JsonObject>> requests = new LinkedHashMap<>();
        for (String station : stations) {
            requests.put(station, mtrops.stationTimetable(station).completeOnTimeout(null, 20, TimeUnit.SECONDS));
        }
        Map<String, Map<String, List<Departure>>> collected = new HashMap<>();
        for (var entry : requests.entrySet()) {
            JsonObject value = entry.getValue().join();
            if (value == null) continue;
            try {
                var routes = new HashMap<String, List<Departure>>();
                for (JsonElement listed : array(value, "departures")) {
                    if (!listed.isJsonObject()) continue;
                    JsonObject item = listed.getAsJsonObject();
                    String route = label(item, "routeId");
                    long departure = (long) number(item, "departureMillis");
                    if (route.isEmpty() || departure <= 0 || flag(item, "pass")) continue;
                    long arrival = (long) number(item, "arrivalMillis");
                    routes.computeIfAbsent(route, k -> new ArrayList<>()).add(new Departure(departure, arrival,
                            label(item, "trainId"), flag(item, "terminating")));
                }
                for (var departures : routes.values()) {
                    departures.sort(Comparator.comparingLong(Departure::time).thenComparing(d -> d.train.isEmpty()));
                }
                collected.put(entry.getKey(), routes);
            } catch (IllegalArgumentException e) {
                // This timetable is unavailable, so loops requiring it cannot be compared.
            }
        }

        Map<String, Map<String, String>> depots = new HashMap<>();
        boolean depotsAvailable = false;
        try {
            for (var listed : array(mtrops.depotList(), "depots")) {
                if (!listed.isJsonObject()) continue;
                JsonObject depot = listed.getAsJsonObject();
                String id = label(depot, "id"), name = label(depot, "name");
                if (id.isEmpty()) continue;
                for (var route : array(depot, "routeIds")) {
                    if (route.isJsonPrimitive()) {
                        depots.computeIfAbsent(route.getAsString(), k -> new HashMap<>()).put(id, name.isEmpty() ? id : name);
                    }
                }
            }
            depotsAvailable = true;
        } catch (IOException | RuntimeException e) {
            depots.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cached = new Timetables(revision, System.currentTimeMillis(), new HashSet<>(stations), collected, depots, depotsAvailable);
        return cached;
    }

    private static List<Trip> trips(Leg leg, Timetables timetable, long now) {
        String routeId = leg.pattern.route().id();
        List<Departure> origins = timetable.forStop(leg.from().id(), routeId);
        List<Departure> destinations = timetable.forStop(leg.to().id(), routeId);
        if (destinations.isEmpty()) return List.of();
        List<Trip> trips = new ArrayList<>();
        long previous = -1;
        for (Departure departure : origins) {
            if (departure.ending || departure.time < now || departure.time == previous) continue;
            long expected = leg.runSeconds > 0 ? departure.time + Math.round(leg.runSeconds * 1000) : -1;
            long arrival = matchingArrival(departure, destinations, expected);
            boolean estimated = arrival < 0;
            if (estimated) arrival = expected;
            if (arrival > departure.time) {
                trips.add(new Trip(departure.time, arrival, departure.train, estimated));
                previous = departure.time;
            }
        }
        return trips;
    }

    private static long matchingArrival(Departure departure, List<Departure> ends, long expected) {
        if (departure.train.isEmpty()) return -1;
        long best = -1, bestDifference = Long.MAX_VALUE;
        long tolerance = expected > 0 ? Math.max(120_000, (expected - departure.time) / 2) : Long.MAX_VALUE;
        for (Departure end : ends) {
            if (end.arrival <= departure.time || !departure.train.equals(end.train)) continue;
            long difference = expected > 0 ? Math.abs(end.arrival - expected) : end.arrival - departure.time;
            if (difference <= tolerance && difference < bestDifference) {
                best = end.arrival;
                bestDifference = difference;
            }
        }
        return best;
    }

    static Cycle plan(List<Trip> outbound, List<Trip> inbound, int transferSeconds) {
        if (outbound.isEmpty() || inbound.isEmpty()) return null;
        long buffer = transferSeconds * 1000L;
        Trip first = outbound.get(0);
        Trip start = first;
        long outRun = 0, backRun = 0, atDestination = 0, atOrigin = 0;
        int loops = 0, estimated = 0, sameTrainTurns = 0;
        while (loops < MAX_CYCLES) {
            Trip back = departureAfter(inbound, start.arrive + buffer);
            if (back == null) break;
            Trip next = departureAfter(outbound, back.arrive + buffer);
            if (next == null) break;
            outRun += start.arrive - start.depart;
            backRun += back.arrive - back.depart;
            atDestination += back.depart - start.arrive;
            atOrigin += next.depart - back.arrive;
            if (start.estimated) estimated++;
            if (back.estimated) estimated++;
            if (sameTrain(start, back)) sameTrainTurns++;
            if (sameTrain(back, next)) sameTrainTurns++;
            loops++;
            start = next;
        }
        if (loops == 0) return null;
        double divisor = 1000.0 * loops;
        return new Cycle(first.depart, (start.depart - first.depart) / divisor,
                outRun / divisor, backRun / divisor, atDestination / divisor, atOrigin / divisor,
                loops, estimated, sameTrainTurns);
    }

    private static boolean sameTrain(Trip first, Trip next) {
        return !first.train.isEmpty() && first.train.equals(next.train);
    }

    private static Trip departureAfter(List<Trip> trips, long time) {
        int low = 0, high = trips.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (trips.get(middle).depart < time) low = middle + 1;
            else high = middle;
        }
        return low < trips.size() ? trips.get(low) : null;
    }

    private static Ride ride(Leg leg, DailyPassFares.Data fares) {
        var route = leg.pattern.route();
        Map<String, Double> companyMeters = Map.of();
        if (fares != null && fares.distanceFare()) {
            try {
                companyMeters = fares.companyDistances(route.name(), leg.pattern.stops().subList(leg.fromIndex, leg.toIndex + 1));
            } catch (ReflectiveOperationException e) {
                // The timetable remains useful when attribution is unavailable.
            }
        }
        return new Ride(route.id(), route.name(), route.number(), leg.meters, leg.measured, companyMeters);
    }

    private record StationPair(String from, String to) {
        StationPair reverse() { return new StationPair(to, from); }
    }
    private record Leg(MtropsRoutes.Pattern pattern, int fromIndex, int toIndex, double meters, double runSeconds, boolean measured) {
        MtropsRoutes.Station from() { return pattern.stops().get(fromIndex); }
        MtropsRoutes.Station to() { return pattern.stops().get(toIndex); }
    }
    private record Departure(long time, long arrival, String train, boolean ending) {}
    private record Timetables(long revision, long loadedAt, HashSet<String> requested,
                              Map<String, Map<String, List<Departure>>> departures,
                              Map<String, Map<String, String>> depots, boolean depotsAvailable) {
        List<Departure> forStop(String station, String route) {
            return departures.getOrDefault(station, Map.of()).getOrDefault(route, List.of());
        }
    }
    record Trip(long depart, long arrive, String train, boolean estimated) {}
    record Cycle(long firstDeparture, double seconds, double outboundSeconds, double returnSeconds,
                 double waitAtDestination, double waitAtOrigin, int cycles, int estimatedRides, int sameTrainTurns) {}
    record Ride(String routeId, String routeName, String type, double meters, boolean measured, Map<String, Double> companyMeters) {}
    record Loop(String startId, String turnId, String start, String turn, Ride outbound, Ride inbound, List<String> sharedDepots, Cycle cycle) {}
    record Result(String state, long measuredAt, int stations, int schedules, boolean depotsAvailable, boolean distanceFare,
                  Map<String, String> companies, List<Loop> loops) {
        static Result unavailable(String state) {
            return new Result(state, 0, 0, 0, false, false, Map.of(), List.of());
        }
    }
}
