package dev.campus.todo;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 手机侧的 GATT 中央端（campus-sync/1）。
 *
 * 流程：按服务 UUID 扫描 → 连接 → 发现服务 → 协商 MTU → 订阅 TX → 分片写快照 → 等 ACK → 重传。
 *
 * 两个刻意的设计：
 *  1. **手机 → 设备走 Write With Response。** ATT 层本来就逐片确认，所以应用层不需要自己
 *     造滑动窗口；真正的完整性判据是整包 CRC，由设备在收齐后校验并回 ACK。
 *  2. **一次只发一个 GATT 操作。** Android 的 GATT 栈不支持并发操作，必须串行化，
 *     否则第二个写会直接返回 false 而静默丢片。
 *
 * 加密特征首次访问会触发系统配对；这里额外注册了配对状态广播，配对完成后自动接着同步，
 * 而不是让用户再点一次。
 */
public final class BleClient {

    private static final String TAG = "BleClient";

    public interface Listener {
        void onStatus(String message);

        void onLog(String line);

        /** 扫描扫到一台设备。rssi 用来在雷达上表示远近（信号强=靠圆心）。 */
        void onDeviceFound(String name, int rssi);

        /** 扫描开始/结束。雷达靠它决定扫掠光带转不转。 */
        void onScanning(boolean active);

        void onDone(boolean ok, String message);
    }

    private static final UUID SERVICE_UUID = UUID.fromString(BleFrame.SERVICE);
    private static final UUID RX_UUID = UUID.fromString(BleFrame.RX);
    private static final UUID TX_UUID = UUID.fromString(BleFrame.TX);
    private static final UUID CCCD_UUID = UUID.fromString(BleFrame.CLIENT_CONFIG);

    private static final int REQUEST_MTU = 247;
    private static final long SCAN_TIMEOUT_MS = 15_000L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    private static final long ACK_TIMEOUT_MS = 8_000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int GATT_INSUFFICIENT_ENCRYPTION = 15;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothGattCharacteristic tx;

    private int chunkSize = BleFrame.chunkSizeFor(23);
    private int transferId = 1;
    private int attempts;
    private boolean writing;
    /** 快照分片队列。 */
    private final Deque<byte[]> writeQueue = new ArrayDeque<>();
    /**
     * 插队队列，目前只放 ACK。
     *
     * 不能和快照共用一个队列还顺手 clear()：设备在收快照的过程中就可能回传操作，
     * 这时 clear() 会把没发完的分片丢掉，快照永远凑不齐，只能靠 ACK 超时重传来兜 ——
     * 能自愈，但白绕一大圈。
     */
    private final Deque<byte[]> urgentQueue = new ArrayDeque<>();

    private BleFrame.Assembler inbound;
    private int inboundTransferId = -1;
    private int inboundType = -1;

    private byte[] pendingSnapshot;
    private int snapshotCount;
    private boolean syncing;
    private boolean bondReceiverRegistered;

    /**
     * 本次是否在走「直连上次设备」这条路，失败时好退回扫描。
     *
     * 必须声明在下面那两个 Runnable 之前：connectTimeout 的 lambda 体里读它，
     * 放在后面会被 javac 判为非法前向引用（编译期就报错，不是运行期问题）。
     */
    private boolean connectingDirect;

    private final Runnable scanTimeout = () -> fail("扫描超时：没找到设备。设备侧需要先跑 campus-sync 固件。");

    /**
     * 用方法引用而不是 lambda 体：lambda 体是字段初始化的一部分，在里面读 ctx
     * （构造器才赋值的 final 字段）会被 javac 判为「变量可能尚未初始化」。
     * 和下面的 ackTimeout 用同一个写法。
     */
    private final Runnable connectTimeout = this::onConnectTimeout;

    private final Runnable ackTimeout = this::onAckTimeout;

    public BleClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    // ------------------------------------------------------------ 前置与权限

    public boolean hasPermissions() {
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    public String describeReadiness() {
        if (adapter == null) {
            return "这台手机没有蓝牙适配器。";
        }
        if (!hasPermissions()) {
            return "还缺蓝牙权限。";
        }
        if (!adapter.isEnabled()) {
            return "蓝牙没打开。";
        }
        return null;
    }

    // ---------------------------------------------------------------- 入口

    /** 扫描 → 连接 → 同步。todos 会以全量快照推给设备。 */
    public void sync(List<Model.Todo> todos) {
        String blocked = describeReadiness();
        if (blocked != null) {
            fail(blocked);
            return;
        }
        pendingSnapshot = TodoSnapshot.encode(todos).getBytes(StandardCharsets.UTF_8);
        snapshotCount = todos == null ? 0 : todos.size();
        attempts = 0;
        transferId = (transferId + 1) & 0xFFFF;

        // 记得地址就直接连，不再要求设备可被发现。
        // 「已配对」通常意味着手机系统正连着设备，而设备在连接期间会停止广播，
        // 这时扫描必然一无所获 —— 先信任记下来的地址，连不上再退回扫描。
        String known = Prefs.deviceAddress(ctx);
        if (!known.isEmpty()) {
            connectDirect(known);
        } else {
            startScan();
        }
    }

    /** 跳过扫描，直接连上次同步成功的设备。 */
    private void connectDirect(String address) {
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            log("记录的地址无效（" + address + "），改为扫描");
            Prefs.setDeviceAddress(ctx, "");
            startScan();
            return;
        }
        connectingDirect = true;
        status("直连上次的设备 " + address + "…");
        log("直连 " + address + "（跳过扫描）");
        onFound(device);
    }

    public void stop() {
        cancelTimers();
        stopScan();
        unregisterBondReceiver();
        syncing = false;
        writeQueue.clear();
        urgentQueue.clear();
        writing = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
    }

    // ---------------------------------------------------------------- 扫描

    private void startScan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("蓝牙扫描器不可用。");
            return;
        }
        status("正在扫描 campus-sync 设备…");
        log("按服务 " + BleFrame.SERVICE + " 过滤扫描");

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(filters, settings, scanCallback);
        } catch (SecurityException e) {
            fail("扫描被拒绝：" + e.getMessage());
            return;
        }
        main.post(() -> listener.onScanning(true));
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        main.post(() -> listener.onScanning(false));
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
                // 权限被中途撤销，没什么可做的
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            reportFound(result.getDevice(), result.getRssi());
            onFound(result.getDevice());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (!results.isEmpty()) {
                ScanResult first = results.get(0);
                reportFound(first.getDevice(), first.getRssi());
                onFound(first.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            fail("扫描失败，错误码 " + errorCode);
        }
    };

    /** 设备名可能因为权限或对方没广播名字而拿不到，统一在这里兜底成地址。 */
    private String deviceLabel(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    /** 告诉界面扫到了一台设备（雷达上要点出来）。扫描回调在 binder 线程，转到主线程。 */
    private void reportFound(BluetoothDevice device, int rssi) {
        String label = deviceLabel(device);
        main.post(() -> listener.onDeviceFound(label, rssi));
    }

    private void onFound(BluetoothDevice device) {
        stopScan();
        String name = deviceLabel(device);
        status("找到 " + name + "，正在连接…");
        log("连接 " + device.getAddress());
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        registerBondReceiver();
        try {
            gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            fail("连接被拒绝：" + e.getMessage());
        }
    }

    // ------------------------------------------------------- 配对状态（自动续跑）

    private void registerBondReceiver() {
        if (bondReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        ctx.registerReceiver(bondReceiver, filter);
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!bondReceiverRegistered) {
            return;
        }
        try {
            ctx.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
            // 没注册成功
        }
        bondReceiverRegistered = false;
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (state == BluetoothDevice.BOND_BONDED) {
                log("配对完成");
                status("配对完成，继续同步…");
                beginTransfer();
            } else if (state == BluetoothDevice.BOND_NONE) {
                log("配对未完成");
            }
        }
    };

    // ---------------------------------------------------------------- GATT

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.removeCallbacks(connectTimeout);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectingDirect = false;  // 连上了，之后断开不再走「直连失败退回扫描」
                log("已连接，请求 MTU " + REQUEST_MTU);
                status("已连接，正在发现服务…");
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    fail("发现服务被拒绝：" + e.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (syncing) {
                    fail("连接已断开（status=" + status + "）。");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("发现服务失败，status=" + status);
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                fail("设备上没有 campus-sync 服务。设备侧的固件还没实现这个协议。");
                return;
            }
            rx = service.getCharacteristic(RX_UUID);
            tx = service.getCharacteristic(TX_UUID);
            if (rx == null || tx == null) {
                fail("服务里缺少 RX/TX 特征。");
                return;
            }
            log("找到服务，请求 MTU " + REQUEST_MTU);
            status("正在协商 MTU…");
            if (!g.requestMtu(REQUEST_MTU)) {
                // 协商失败不算致命：退回默认 MTU 继续，只是分片会多些。
                log("requestMtu 被拒绝，用默认 MTU 继续");
                chunkSize = BleFrame.chunkSizeFor(23);
                subscribe();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            chunkSize = BleFrame.chunkSizeFor(status == BluetoothGatt.GATT_SUCCESS ? mtu : 23);
            log("MTU=" + mtu + "，每片载荷 " + chunkSize + " 字节");
            subscribe();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!TX_UUID.equals(descriptor.getCharacteristic().getUuid())) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("订阅 TX 通知失败，status=" + status);
                return;
            }
            log("已订阅设备通知");
            beginTransfer();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic,
                                          int status) {
            writing = false;
            if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) {
                // 加密特征首次访问：让系统弹配对，配对完成后广播会接着往下走。
                log("需要配对（status=" + status + "），发起配对");
                status("请在手机上确认配对，并按设备屏幕显示的配对码操作");
                BluetoothDevice device = g.getDevice();
                try {
                    if (device.getBondState() != BluetoothDevice.BOND_BONDING) {
                        device.createBond();
                    }
                } catch (SecurityException e) {
                    fail("发起配对失败：" + e.getMessage());
                }
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("写分片失败，status=" + status);
                return;
            }
            pump();
        }

        // API 33 起系统走这个重载；下面那个留给更老的版本。两个都覆写，行为汇到一处。
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handleNotify(value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            handleNotify(characteristic.getValue());
        }
    };

    private void subscribe() {
        if (gatt == null || tx == null) {
            return;
        }
        status("正在订阅设备通知…");
        try {
            if (!gatt.setCharacteristicNotification(tx, true)) {
                fail("打开本地通知开关失败。");
                return;
            }
            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                fail("TX 特征没有 CCCD 描述符。");
                return;
            }
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                ok = gatt.writeDescriptor(cccd);
            }
            if (!ok) {
                fail("写 CCCD 失败。");
            }
        } catch (SecurityException e) {
            fail("订阅被拒绝：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 发送

    private void beginTransfer() {
        if (gatt == null || rx == null || pendingSnapshot == null) {
            return;
        }
        attempts++;
        syncing = true;
        releaseAckTimer();

        List<byte[]> frames = BleFrame.chunk(
                BleFrame.TYPE_SNAPSHOT, transferId, pendingSnapshot, chunkSize);
        writeQueue.clear();
        writeQueue.addAll(frames);
        log("推送快照 " + pendingSnapshot.length + " 字节，分 " + frames.size()
                + " 片（第 " + attempts + " 次尝试）");
        status("正在推送 " + snapshotCount + " 条待办…共 " + frames.size() + " 片");
        pump();
    }

    /** 一次只发一个操作：Android 的 GATT 栈不支持并发。 */
    private void pump() {
        if (writing || gatt == null || rx == null) {
            return;
        }
        byte[] frame = urgentQueue.poll();
        if (frame == null) {
            frame = writeQueue.poll();
        }
        if (frame == null) {
            armAckTimer();
            return;
        }
        writing = true;
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(rx, frame,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            } else {
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                rx.setValue(frame);
                ok = gatt.writeCharacteristic(rx);
            }
            if (!ok) {
                writing = false;
                fail("发起写入失败（多余的写入会被栈直接丢弃）。");
            }
        } catch (SecurityException e) {
            writing = false;
            fail("写入被拒绝：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 接收

    private void handleNotify(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        BleFrame.Chunk chunk = BleFrame.decode(value, value.length);
        if (chunk == null) {
            log("收到无法识别的帧 " + value.length + "B");
            return;
        }

        if (inbound == null || inboundTransferId != chunk.transferId || inboundType != chunk.type) {
            inboundTransferId = chunk.transferId;
            inboundType = chunk.type;
            inbound = new BleFrame.Assembler(chunk.total, chunk.payloadCrc);
        }

        BleFrame.Assembler.Result result = inbound.offer(chunk.index, chunk.payload);
        if (result == BleFrame.Assembler.Result.NEED_MORE) {
            return;
        }
        byte[] payload = inbound.payload();
        inbound = null;
        int type = chunk.type;
        int id = chunk.transferId;

        if (result == BleFrame.Assembler.Result.CRC_FAIL) {
            log("收到整包但 CRC 不符（transfer " + id + "）");
            return;
        }
        if (type == BleFrame.TYPE_ACK) {
            onAck(BleFrame.ackTransferId(payload), BleFrame.ackStatus(payload));
        } else if (type == BleFrame.TYPE_OP) {
            onOp(id, new String(payload, StandardCharsets.UTF_8));
        } else {
            log("收到未处理的帧类型 0x" + Integer.toHexString(type));
        }
    }

    private void onAck(int ackedTransfer, int ackStatus) {
        if (ackedTransfer != transferId) {
            log("收到旧 transfer 的 ACK（" + ackedTransfer + "），忽略");
            return;
        }
        releaseAckTimer();
        syncing = false;
        if (ackStatus == BleFrame.ACK_OK) {
            log("设备确认收到快照");
            done(true, "同步完成：" + snapshotCount + " 条待办已推到设备");
        } else if (ackStatus == BleFrame.ACK_CRC_FAIL && attempts < MAX_ATTEMPTS) {
            log("设备回报 CRC 不符，重传");
            status("设备校验失败，正在重传…");
            transferId = (transferId + 1) & 0xFFFF;
            beginTransfer();
        } else {
            fail("设备拒绝了快照（status=" + ackStatus + "）。");
        }
    }

    private void onOp(int transferIdFromDevice, String text) {
        Model.DeviceOp op = TodoSnapshot.decodeOp(text);
        if (op == null) {
            log("收到无法解析的设备操作：" + text);
            sendAck(transferIdFromDevice, BleFrame.ACK_PARSE_FAIL);
            return;
        }
        String result = applyOp(ctx, op);
        log("设备操作 #" + op.devSeq + " " + op.action + " todo=" + op.todoId + " → " + result);
        sendAck(transferIdFromDevice, "TODO_NOT_FOUND".equals(result)
                ? BleFrame.ACK_PARSE_FAIL : BleFrame.ACK_OK);
    }

    private void sendAck(int forTransfer, int status) {
        if (gatt == null || rx == null) {
            return;
        }
        byte[] ackPayload = BleFrame.encodeAck(forTransfer, status);
        byte[] frame = BleFrame.encode(BleFrame.TYPE_ACK, forTransfer, 0, 1,
                BleFrame.crc16(ackPayload), ackPayload);
        urgentQueue.add(frame);   // 插队，且不动快照队列
        pump();
    }

    /**
     * 把设备上的操作落到本地库，并重排提醒。
     * 找不到对应待办时返回 TODO_NOT_FOUND，由调用方回一个失败 ACK —— 静默吞掉会让设备
     * 以为操作生效了，两边状态就永久不一致。
     */
    public static String applyOp(Context ctx, Model.DeviceOp op) {
        Db db = Db.get(ctx);
        Model.Todo todo = db.getTodo(op.todoId);
        if (todo == null) {
            return "TODO_NOT_FOUND";
        }
        switch (op.action) {
            case Model.DeviceOp.DONE:
                db.setTodoStatus(todo.id, Model.STATUS_DONE);
                todo.status = Model.STATUS_DONE;
                Reminder.cancel(ctx, todo);
                return "DONE";
            case Model.DeviceOp.UNDONE:
                db.setTodoStatus(todo.id, Model.STATUS_ACTIVE);
                todo.status = Model.STATUS_ACTIVE;
                Reminder.schedule(ctx, todo);
                return "UNDONE";
            case Model.DeviceOp.SNOOZE:
                long until = System.currentTimeMillis()
                        + Math.max(1, op.snoozeMinutes) * 60_000L;
                db.setTodoSnooze(todo.id, until);
                db.setTodoStatus(todo.id, Model.STATUS_ACTIVE);
                todo.snoozeUntil = until;
                todo.status = Model.STATUS_ACTIVE;
                Reminder.schedule(ctx, todo);
                return "SNOOZE";
            default:
                return "TODO_NOT_FOUND";
        }
    }

    // ------------------------------------------------------------ 超时与收尾

    private void armAckTimer() {
        if (!syncing) {
            return;
        }
        releaseAckTimer();
        main.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
    }

    private void releaseAckTimer() {
        main.removeCallbacks(ackTimeout);
    }

    private void onAckTimeout() {
        if (!syncing) {
            return;
        }
        if (attempts < MAX_ATTEMPTS) {
            log("等 ACK 超时，重传（第 " + (attempts + 1) + " 次）");
            status("设备没回应，正在重传…");
            transferId = (transferId + 1) & 0xFFFF;
            beginTransfer();
        } else {
            fail("重传 " + MAX_ATTEMPTS + " 次都没有收到设备确认。");
        }
    }

    private void cancelTimers() {
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(ackTimeout);
    }

    private void onConnectTimeout() {
        if (connectingDirect) {
            // 直连没成（设备换了地址，或绑定已被清掉）。清掉记录退回扫描，
            // 而不是直接甩一句「连接超时」把用户堵在这里。
            log("直连超时，清除记录并改为扫描");
            Prefs.setDeviceAddress(ctx, "");
            connectingDirect = false;
            if (gatt != null) {
                try {
                    gatt.close();
                } catch (Exception ignored) {
                    // close 失败没有补救手段
                }
                gatt = null;
            }
            startScan();
            return;
        }
        fail("连接超时。");
    }

    private void fail(String message) {
        cancelTimers();
        stopScan();
        syncing = false;
        writeQueue.clear();
        urgentQueue.clear();
        writing = false;
        log("失败：" + message);
        listener.onStatus("未完成");
        listener.onDone(false, message);
    }

    private void done(boolean ok, String message) {
        cancelTimers();
        writeQueue.clear();
        urgentQueue.clear();
        writing = false;
        connectingDirect = false;
        main.post(() -> listener.onScanning(false));  // 直连这条路没有 stopScan，单独补一下
        if (ok && gatt != null) {
            try {
                // 记下地址：下次直接连，不必再指望设备在广播。
                Prefs.setDeviceAddress(ctx, gatt.getDevice().getAddress());
            } catch (SecurityException ignored) {
                // 拿不到地址就算了，下次照样扫描
            }
        }
        listener.onStatus(ok ? "已同步" : "未完成");
        listener.onDone(ok, message);
    }

    private void status(String message) {
        Log.i(TAG, message);
        main.post(() -> listener.onStatus(message));
    }

    private void log(String line) {
        Log.i(TAG, line);
        String text = line;
        main.post(() -> listener.onLog(text));
    }
}
