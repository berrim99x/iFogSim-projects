package org.fog.test.myprojects;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.*;
import org.fog.placement.*;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.*;
import org.fog.utils.distribution.DeterministicDistribution;

import java.util.*;

public class EdgeComputingSimulation {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        // =====  Edge Computing =====
        System.out.println("Edge Computing (معالجة محلية)");
        SimulationResult edgeResult = runEdgeSimulation();

        fogDevices.clear();
        sensors.clear();
        actuators.clear();


    }

    // ================================================================
    //  السيناريو 1 — Edge Computing
    // ================================================================
    private static SimulationResult runEdgeSimulation() throws Exception {
        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);

        String appId  = "edge-app";
        int    userId = 1;
        Application app = createSurveillanceApp(appId, userId);

        // Cloud (Level 0)
        FogDevice cloud = createFogDevice("cloud", 44800, 40000,
                100, 10000, 0, 0.01, 16 * 103.0, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        // Edge Gateway (Level 1) — 10 ms إلى السحابة
        FogDevice gateway = createFogDevice("edge-gateway", 2800, 4000,
                10000, 10000, 1, 0.0, 107.339, 83.4333);
        gateway.setParentId(cloud.getId());
        gateway.setUplinkLatency(10);
        fogDevices.add(gateway);

        // Edge Server (Level 2) — 1 ms محلي
        FogDevice edgeServer = createFogDevice("edge-server", 1000, 1000,
                10000, 270, 2, 0.0, 87.53, 82.44);
        edgeServer.setParentId(gateway.getId());
        edgeServer.setUplinkLatency(1);
        fogDevices.add(edgeServer);

        // Sensor
        Sensor camera = new Sensor("camera-sensor", "VIDEO_FRAME",
                userId, appId, new DeterministicDistribution(1));
        camera.setGatewayDeviceId(edgeServer.getId());
        camera.setLatency(0.5);
        sensors.add(camera);

        // Actuator
        Actuator display = new Actuator("display", userId, appId, "DISPLAY");
        display.setGatewayDeviceId(edgeServer.getId());
        display.setLatency(0.5);
        actuators.add(display);

        // وضع الوحدات على الحافة
        ModuleMapping edgeMapping = ModuleMapping.createModuleMapping();
        edgeMapping.addModuleToDevice("motion-detector", "edge-server");
        edgeMapping.addModuleToDevice("object-tracker",  "edge-gateway");
        edgeMapping.addModuleToDevice("alert-manager",   "cloud");

        Controller controller = new Controller("ctrl-edge",
                fogDevices, sensors, actuators);
        controller.submitApplication(app,
                new ModulePlacementEdgewards(fogDevices, sensors,
                        actuators, app, edgeMapping));

        TimeKeeper.getInstance().setSimulationStartTime(
                Calendar.getInstance().getTimeInMillis());
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        return collectResults("Edge Computing");
    }

    // ================================================================
    //  السيناريو 2 — Cloud Only
    // ================================================================
    private static SimulationResult runCloudOnlySimulation() throws Exception {
        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);

        String appId  = "cloud-app";
        int    userId = 1;
        Application app = createSurveillanceApp(appId, userId);

        // Cloud (Level 0)
        FogDevice cloud = createFogDevice("cloud", 44800, 40000,
                100, 10000, 0, 0.01, 16 * 103.0, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        // Gateway ضعيف — 100 ms إلى السحابة
        FogDevice gateway = createFogDevice("gateway", 500, 1000,
                10000, 10000, 1, 0.0, 107.339, 83.4333);
        gateway.setParentId(cloud.getId());
        gateway.setUplinkLatency(100);
        fogDevices.add(gateway);

        // End Device ضعيف جداً
        FogDevice endDevice = createFogDevice("end-device", 300, 512,
                10000, 270, 2, 0.0, 87.53, 82.44);
        endDevice.setParentId(gateway.getId());
        endDevice.setUplinkLatency(2);
        fogDevices.add(endDevice);

        // Sensor
        Sensor camera = new Sensor("camera", "VIDEO_FRAME",
                userId, appId, new DeterministicDistribution(1));
        camera.setGatewayDeviceId(endDevice.getId());
        camera.setLatency(0.5);
        sensors.add(camera);

        // Actuator
        Actuator display = new Actuator("display", userId, appId, "DISPLAY");
        display.setGatewayDeviceId(endDevice.getId());
        display.setLatency(0.5);
        actuators.add(display);

        // كل الوحدات على السحابة
        ModuleMapping cloudMapping = ModuleMapping.createModuleMapping();
        cloudMapping.addModuleToDevice("motion-detector", "cloud");
        cloudMapping.addModuleToDevice("object-tracker",  "cloud");
        cloudMapping.addModuleToDevice("alert-manager",   "cloud");

        Controller controller = new Controller("ctrl-cloud",
                fogDevices, sensors, actuators);
        controller.submitApplication(app,
                new ModulePlacementMapping(fogDevices, sensors,
                        actuators, app, cloudMapping));

        TimeKeeper.getInstance().setSimulationStartTime(
                Calendar.getInstance().getTimeInMillis());
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        return collectResults("Cloud Only");
    }

    // ================================================================
    //  تعريف تطبيق المراقبة
    // ================================================================
    private static Application createSurveillanceApp(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);

        app.addAppModule("motion-detector", 10);
        app.addAppModule("object-tracker",  10);
        app.addAppModule("alert-manager",   10);

        // VIDEO_FRAME → motion-detector
        app.addAppEdge("VIDEO_FRAME", "motion-detector",
                2000, 2000, "VIDEO_FRAME",
                Tuple.UP, AppEdge.SENSOR);

        // motion-detector → object-tracker
        app.addAppEdge("motion-detector", "object-tracker",
                500, 500, "MOTION_EVENT",
                Tuple.UP, AppEdge.MODULE);

        // object-tracker → alert-manager
        app.addAppEdge("object-tracker", "alert-manager",
                100, 100, "TRACKED_OBJECT",
                Tuple.UP, AppEdge.MODULE);

        // alert-manager → DISPLAY
        app.addAppEdge("alert-manager", "DISPLAY",
                100, 28, 50, "ALERT_SIGNAL",
                Tuple.DOWN, AppEdge.ACTUATOR);

        // Selectivity: فقط 5% من الإطارات تحتوي على حركة
        app.addTupleMapping("motion-detector", "VIDEO_FRAME",
                "MOTION_EVENT",   new FractionalSelectivity(0.05));
        app.addTupleMapping("object-tracker",  "MOTION_EVENT",
                "TRACKED_OBJECT", new FractionalSelectivity(1.0));
        app.addTupleMapping("alert-manager",   "TRACKED_OBJECT",
                "ALERT_SIGNAL",   new FractionalSelectivity(1.0));

        return app;
    }

    // ================================================================
    //  بناء FogDevice — الدالة الكاملة
    // ================================================================
    private static FogDevice createFogDevice(
            String name, long mips, int ram,
            long upBw, long downBw, int level,
            double ratePerMips, double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));

        PowerHost host = new PowerHost(
                FogUtils.generateEntityId(),
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(1000000),
                1000000,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics chars = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen",
                host, 10.0, 3.0, 0.05, 0.001, 0.0);

        FogDevice device = null;
        try {
            device = new FogDevice(name, chars,
                    new AppModuleAllocationPolicy(hostList),
                    new ArrayList<>(),
                    10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }

        assert device != null;
        device.setLevel(level);
        return device;
    }

    // ================================================================
    //  جمع النتائج بعد كل محاكاة
    // ================================================================
    private static SimulationResult collectResults(String scenario) {
        SimulationResult result = new SimulationResult();
        result.scenario = scenario;

        // متوسط التأخير
        Map<Integer, Double> latencyMap =
                TimeKeeper.getInstance().getLoopIdToCurrentAverage();
        result.avgLatency = latencyMap.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        // حمل الشبكة
        result.networkUsage = NetworkUsageMonitor.getNetworkUsage()
                / Config.MAX_SIMULATION_TIME;

        // إجمالي استهلاك الطاقة
        result.energyConsumption = fogDevices.stream()
                .mapToDouble(FogDevice::getEnergyConsumption)
                .sum();

        // طباعة فورية لكل سيناريو
        System.out.println("  التأخير    : " + String.format("%.2f", result.avgLatency)       + " ms");
        System.out.println("  الشبكة     : " + String.format("%.2f", result.networkUsage)     + " KB/s");
        System.out.println("  الطاقة     : " + String.format("%.2f", result.energyConsumption)+ " J");

        return result;
    }

    // ================================================================
    //  جدول المقارنة النهائي
    // ================================================================
    private static void printComparison(SimulationResult edge,
                                        SimulationResult cloud) {
        double latencyImprove = safeImprove(cloud.avgLatency,       edge.avgLatency);
        double networkImprove = safeImprove(cloud.networkUsage,     edge.networkUsage);
        double energyImprove  = safeImprove(cloud.energyConsumption,edge.energyConsumption);

        System.out.println("\n╔═══════════════════════════════════════════════════════╗");
        System.out.println("║               جدول المقارنة النهائي                   ║");
        System.out.println("╠═══════════════════╦══════════════╦════════════════════╣");
        System.out.println("║ المقياس           ║ Edge         ║ Cloud Only         ║");
        System.out.println("╠═══════════════════╬══════════════╬════════════════════╣");
        System.out.printf ("║ التأخير (ms)      ║ %-12.2f ║ %-18.2f ║%n",
                edge.avgLatency,        cloud.avgLatency);
        System.out.printf ("║ حمل الشبكة (KB/s) ║ %-12.2f ║ %-18.2f ║%n",
                edge.networkUsage,      cloud.networkUsage);
        System.out.printf ("║ الطاقة (J)        ║ %-12.2f ║ %-18.2f ║%n",
                edge.energyConsumption, cloud.energyConsumption);
        System.out.println("╠═══════════════════╩══════════════╩════════════════════╣");
        System.out.printf ("║ تحسين التأخير: %5.1f%%   حمل الشبكة: %5.1f%%            ║%n",
                latencyImprove, networkImprove);
        System.out.printf ("║ تحسين الطاقة:  %5.1f%%                                  ║%n",
                energyImprove);
        System.out.println("╚═══════════════════════════════════════════════════════╝");
    }

    private static double safeImprove(double cloud, double edge) {
        if (cloud == 0) return 0;
        return ((cloud - edge) / cloud) * 100.0;
    }
}

// ================================================================
//  كلاس تخزين النتائج
// ================================================================
class SimulationResult {
    String scenario;
    double avgLatency;
    double networkUsage;
    double energyConsumption;
}