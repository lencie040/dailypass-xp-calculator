package jp.dailypassxplink;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DailyPassFares {
    private final Method readTables;
    private final Method usesDistanceFare;
    private final Method listCompanies;
    private final Method companyId;
    private final Method companyName;
    private final Constructor<?> stop;
    private final Method calculate;
    private final Method segments;
    private final Method segmentCompany;
    private final Method segmentMeters;

    DailyPassFares() throws ReflectiveOperationException {
        Class<?> companyData = Class.forName("jp.dailypass.client.CompanyData");
        Class<?> company = Class.forName("jp.dailypass.client.CompanyData$Company");
        Class<?> calculator = Class.forName("jp.dailypass.fare.FareCalculator");
        Class<?> stopType = Class.forName("jp.dailypass.fare.FareCalculator$Stop");
        Class<?> segment = Class.forName("jp.dailypass.fare.FareCalculator$Segment");

        readTables = companyData.getMethod("tables");
        usesDistanceFare = companyData.getMethod("fareByMod");
        listCompanies = companyData.getMethod("all");
        companyId = company.getMethod("id");
        companyName = company.getMethod("name");
        stop = stopType.getConstructor(String.class, double.class);
        calculate = calculator.getMethod("calculate", List.class, String.class, List.class,
                double.class, double.class, boolean.class, boolean.class, readTables.getReturnType());
        segments = calculate.getReturnType().getMethod("segments");
        segmentCompany = segment.getMethod("company");
        segmentMeters = segment.getMethod("meters");
    }

    Data capture() throws ReflectiveOperationException {
        Object tables = readTables.invoke(null);
        boolean distanceFare = (boolean) usesDistanceFare.invoke(null);
        Map<String, String> names = new LinkedHashMap<>();
        for (Object company : (List<?>) listCompanies.invoke(null)) {
            names.put((String) companyId.invoke(company), (String) companyName.invoke(company));
        }
        return new Data(this, tables, distanceFare, Map.copyOf(names));
    }

    private Map<String, Double> companyDistances(Object tables, String routeName, List<MtropsRoutes.Station> stations)
            throws ReflectiveOperationException {
        List<Object> stops = new ArrayList<>();
        double start = stations.get(0).meters();
        double end = stations.get(stations.size() - 1).meters();
        for (MtropsRoutes.Station station : stations) {
            stops.add(stop.newInstance(station.name(), station.meters() - start));
        }

        // Use DailyPass's own station-code attribution and its minimum segment length.
        // A route estimate has no train ID, so train-ID-only fare assignments are left unresolved.
        Object result = calculate.invoke(null, List.of(routeName), "", stops, 0, end - start, true, false, tables);
        Map<String, Double> meters = new LinkedHashMap<>();
        for (Object piece : (List<?>) segments.invoke(result)) {
            double distance = (double) segmentMeters.invoke(piece);
            if (Double.isFinite(distance) && distance > 0) {
                meters.merge((String) segmentCompany.invoke(piece), distance, Double::sum);
            }
        }
        return meters;
    }

    record Data(DailyPassFares adapter, Object tables, boolean distanceFare, Map<String, String> names) {
        Map<String, Double> companyDistances(String routeName, List<MtropsRoutes.Station> stations)
                throws ReflectiveOperationException {
            return adapter.companyDistances(tables, routeName, stations);
        }
    }
}
