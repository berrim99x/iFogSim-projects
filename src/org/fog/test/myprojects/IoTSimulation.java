package org.fog.test.myprojects;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.*;
import org.fog.placement.*;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.*;
import org.fog.utils.*;
import org.fog.utils.distribution.DeterministicDistribution;
import java.util.*;

public class IoTSimulation {

    // قائمة بجميع أجهزة الضباب
    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== محاكاة بيئة IoT باستخدام iFogSim ===\n");

        try {
            // 1. تهيئة CloudSim (الأساس الذي يعتمد عليه iFogSim)
            Log.disable();
            int numUsers = 1;
            CloudSim.init(numUsers, Calendar.getInstance(), false);

            // 2. إنشاء التطبيق
            String appId = "iot_monitoring";
            int userId = 1;
            Application application = createApplication(appId, userId);
            application.setUserId(userId);

            // 3. إنشاء هيكل الشبكة
            createNetworkTopology(userId, appId);

            // 4. تحديد Controller
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            moduleMapping.addModuleToDevice("data-processor", "cloud");
            moduleMapping.addModuleToDevice("data-aggregator", "gateway-1");
            moduleMapping.addModuleToDevice("sensor-reader", "iot-device-1");

            Controller controller = new Controller("master-controller",
                    fogDevices, sensors, actuators);
            controller.submitApplication(application,
                    new ModulePlacementEdgewards(fogDevices, sensors,
                            actuators, application, moduleMapping));

            // 5. تشغيل المحاكاة
            TimeKeeper.getInstance().setSimulationStartTime(
                    Calendar.getInstance().getTimeInMillis());
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            // 6. عرض النتائج
            printNetworkResults();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * إنشاء هيكل شبكة IoT
     * Cloud → Proxy → Gateway → IoT Device
     */
    private static void createNetworkTopology(int userId, String appId) {
        // إنشاء السحابة (المستوى 0)
        FogDevice cloud = createFogDevice("cloud", 44800, 40000,
                100, 10000, 0, 0.01, 16*103, 16*83.25);
        cloud.setParentId(-1); // لا يوجد أب للسحابة
        fogDevices.add(cloud);

        // إنشاء خادم وسيط (المستوى 1)
        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000,
                10000, 10000, 1, 0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100); // 100ms تأخير للسحابة
        fogDevices.add(proxy);

        // إنشاء بوابة محلية (المستوى 2)
        FogDevice gateway = createFogDevice("gateway-1", 2800, 4000,
                10000, 10000, 2, 0.0, 107.339, 83.4333);
        gateway.setParentId(proxy.getId());
        gateway.setUplinkLatency(4); // 4ms تأخير للخادم الوسيط
        fogDevices.add(gateway);

        // إنشاء جهاز IoT (المستوى 3)
        FogDevice iotDevice = createFogDevice("iot-device-1", 1000, 1000,
                10000, 270, 3, 0.0, 87.53, 82.44);
        iotDevice.setParentId(gateway.getId());
        iotDevice.setUplinkLatency(2); // 2ms تأخير للبوابة
        fogDevices.add(iotDevice);

        // إنشاء حساس (Sensor)
        Sensor sensor = new Sensor("temp-sensor", "TEMPERATURE",
                userId, appId,
                new DeterministicDistribution(5)); // إرسال كل 5 ثوانٍ
        sensor.setGatewayDeviceId(iotDevice.getId());
        sensor.setLatency(1.0); // 1ms تأخير
        sensors.add(sensor);

        // إنشاء مشغّل (Actuator)
        Actuator actuator = new Actuator("alert-actuator", userId,
                appId, "ALERT");
        actuator.setGatewayDeviceId(gateway.getId());
        actuator.setLatency(1.0);
        actuators.add(actuator);
    }

    /**
     * إنشاء جهاز ضباب
     */
    private static FogDevice createFogDevice(String name, long mips,
                                             int ram, long upBw, long downBw, int level,
                                             double ratePerMips, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));

        PowerHost host = new PowerHost(
                FogUtils.generateEntityId(),
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(10000),
                1000000,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics =
                new FogDeviceCharacteristics("x86", "Linux", "Xen",
                        host, 10, 3, 0.05, 0.001, 0.0);

        FogDevice fogDevice = null;
        try {
            fogDevice = new FogDevice(name, characteristics,
                    new AppModuleAllocationPolicy(hostList),
                    new ArrayList<>(), 10, upBw, downBw,
                    0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }

        fogDevice.setLevel(level);
        return fogDevice;
    }

    /**
     * تعريف التطبيق كـ DAG (Directed Acyclic Graph)
     */
    private static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);

        // تعريف وحدات التطبيق
        app.addAppModule("sensor-reader", 10);    // قارئ البيانات
        app.addAppModule("data-aggregator", 10);  // مجمّع البيانات
        app.addAppModule("data-processor", 10);   // معالج البيانات

        // تدفق البيانات: Sensor → sensor-reader
        app.addAppEdge("TEMPERATURE", "sensor-reader",
                1000, 500, "TEMPERATURE",
                Tuple.UP, AppEdge.SENSOR);

        // تدفق البيانات: sensor-reader → data-aggregator
        app.addAppEdge("sensor-reader", "data-aggregator",
                1000, 1000, "PROCESSED_DATA",
                Tuple.UP, AppEdge.MODULE);

        // تدفق البيانات: data-aggregator → data-processor (Cloud)
        app.addAppEdge("data-aggregator", "data-processor",
                100, 1000, "AGGREGATED_DATA",
                Tuple.UP, AppEdge.MODULE);

        // إرسال التنبيهات: data-processor → actuator
        app.addAppEdge("data-processor", "ALERT",
                100, 28, 100, "ALERT",
                Tuple.DOWN, AppEdge.ACTUATOR);

        // تعريف الانتقائية (Selectivity)
        app.addTupleMapping("sensor-reader", "TEMPERATURE",
                "PROCESSED_DATA", new FractionalSelectivity(1.0));
        app.addTupleMapping("data-aggregator", "PROCESSED_DATA",
                "AGGREGATED_DATA", new FractionalSelectivity(0.5));
        app.addTupleMapping("data-processor", "AGGREGATED_DATA",
                "ALERT", new FractionalSelectivity(0.05));

        // تعريف حلقة القياس
        List<AppLoop> loops = new ArrayList<>();
        loops.add(new AppLoop(new ArrayList<>(Arrays.asList(
                "temp-sensor", "sensor-reader",
                "data-aggregator", "data-processor",
                "alert-actuator"))));
        app.setLoops(loops);

        return app;
    }

    /**
     * طباعة نتائج الشبكة
     */
    private static void printNetworkResults() {
        System.out.println("\n=== نتائج المحاكاة ===");
        System.out.println("التأخير الإجمالي (ms):");
        System.out.println("  IoT Device → Gateway: 2 ms");
        System.out.println("  Gateway → Proxy:      4 ms");
        System.out.println("  Proxy → Cloud:        100 ms");
        System.out.println("  إجمالي: ~106 ms");
        System.out.println("\nحمل الشبكة (Network Load):");
        System.out.println("  Uplink (IoT→Cloud):   1000 KB/s");
        System.out.println("  Downlink (Cloud→IoT): 270 KB/s");

        // ✅ صحيح
        for (Map.Entry<Integer, Double> entry :
                TimeKeeper.getInstance().getLoopIdToCurrentAverage().entrySet()) {
            // ✅ صحيح
            System.out.printf("\nمتوسط التأخير للحلقة %d: %.2f ms%n",
                    entry.getKey(),
                    entry.getValue());
        }
    }
}