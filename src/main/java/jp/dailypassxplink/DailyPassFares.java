package jp.dailypassxplink;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DailyPassFares {
    private final Method readTables;
    private final Method usesDistanceFare;
    private final Method listCompanies;
    private final Method companyId;
    private final Method companyName;
    private final Method companyFares;
    private final Field configInstance;
    private final Field fareDivisor;
    private final Constructor<?> stop;
    private final Method calculate;
    private final Method segments;
    private final Method total;
    private final Method segmentCompany;
    private final Method segmentMeters;

    DailyPassFares() throws ReflectiveOperationException {
        Class<?> companyData = Class.forName("jp.dailypass.client.CompanyData");
        Class<?> company = Class.forName("jp.dailypass.client.CompanyData$Company");
        Class<?> calculator = Class.forName("jp.dailypass.fare.FareCalculator");
        Class<?> stopType = Class.forName("jp.dailypass.fare.FareCalculator$Stop");
        Class<?> segment = Class.forName("jp.dailypass.fare.FareCalculator$Segment");
        Class<?> config = Class.forName("jp.dailypass.DailyPassConfig");

        readTables = companyData.getMethod("tables");
        usesDistanceFare = companyData.getMethod("fareByMod");
        listCompanies = companyData.getMethod("all");
        companyId = company.getMethod("id");
        companyName = company.getMethod("name");
        companyFares = company.getMethod("fares");
        configInstance = config.getField("INSTANCE");
        fareDivisor = config.getField("fareDivisor");
        stop = stopType.getConstructor(String.class, double.class);
        calculate = calculator.getMethod("calculate", List.class, String.class, List.class,
                double.class, double.class, boolean.class, boolean.class, readTables.getReturnType());
        segments = calculate.getReturnType().getMethod("segments");
        total = calculate.getReturnType().getMethod("total");
        segmentCompany = segment.getMethod("company");
        segmentMeters = segment.getMethod("meters");
    }

    Data capture() throws ReflectiveOperationException {
        Object tables = readTables.invoke(null);
        boolean distanceFare = (boolean) usesDistanceFare.invoke(null);
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> priced = new HashSet<>();
        for (Object company : (List<?>) listCompanies.invoke(null)) {
            String id = (String) companyId.invoke(company);
            names.put(id, (String) companyName.invoke(company));
            if (!((List<?>) companyFares.invoke(company)).isEmpty()) priced.add(id);
        }
        int divisor = Math.max(1, fareDivisor.getInt(configInstance.get(null)));
        return new Data(this, tables, distanceFare, Map.copyOf(names), Set.copyOf(priced), divisor);
    }

    private Quote quote(Data data, String routeName, List<MtropsRoutes.Station> stations)
            throws ReflectiveOperationException {
        List<Object> stops = new ArrayList<>();
        double start = stations.get(0).meters();
        double end = stations.get(stations.size() - 1).meters();
        for (MtropsRoutes.Station station : stations) {
            stops.add(stop.newInstance(station.name(), station.meters() - start));
        }

        // Use DailyPass's own station-code attribution and its minimum segment length.
        // A route estimate has no train ID, so train-ID-only fare assignments are left unresolved.
        Object result = calculate.invoke(null, List.of(routeName), "", stops, 0, end - start, true, false, data.tables);
        Map<String, Double> meters = new LinkedHashMap<>();
        for (Object piece : (List<?>) segments.invoke(result)) {
            double distance = (double) segmentMeters.invoke(piece);
            if (Double.isFinite(distance) && distance > 0) {
                meters.merge((String) segmentCompany.invoke(piece), distance, Double::sum);
            }
        }
        int baseFare = (int) total.invoke(result);
        double attributedMeters = meters.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean priced = !meters.isEmpty() && data.pricedCompanies.containsAll(meters.keySet())
                && Math.abs(attributedMeters - (end - start)) < 0.01 && baseFare >= 0;
        return new Quote(meters, priced ? baseFare / data.fareDivisor : null);
    }

    record Quote(Map<String, Double> companyMeters, Integer regularFareCp) {
        static final Quote UNKNOWN = new Quote(Map.of(), null);
    }

    record Data(DailyPassFares adapter, Object tables, boolean distanceFare, Map<String, String> names,
                Set<String> pricedCompanies, int fareDivisor) {
        Quote quote(String routeName, List<MtropsRoutes.Station> stations)
                throws ReflectiveOperationException {
            return adapter.quote(this, routeName, stations);
        }
    }
}
