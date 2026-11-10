package io.romgold.airmouse;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.Executor;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_BT = 41, REPORT_ID = 1;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice target;
    private SensorManager sensors;
    private Sensor gyro;
    private boolean airMode = false, registered = false;
    private float sensitivity = 1.4f, smooth = .35f, filteredX, filteredY;
    private long lastSensor;
    private TextView status, mode, deviceName;
    private LinearLayout root;
    private final Executor executor = command -> new Handler(Looper.getMainLooper()).post(command);

    private static final byte[] MOUSE_DESCRIPTOR = new byte[] {
        0x05,0x01,0x09,0x02,(byte)0xA1,0x01,0x09,0x01,(byte)0xA1,0x00,(byte)0x85,0x01,
        0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,
        (byte)0x95,0x03,0x75,0x01,(byte)0x81,0x02,
        (byte)0x95,0x01,0x75,0x05,(byte)0x81,0x03,
        0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,
        0x15,(byte)0x81,0x25,0x7F,(byte)0x75,0x08,(byte)0x95,0x03,(byte)0x81,0x06,
        (byte)0xC0,(byte)0xC0
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = (BluetoothHidDevice) proxy;
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "Air Mouse", "Phone gyro and touchpad mouse", "Romgold", BluetoothHidDevice.SUBCLASS1_MOUSE, MOUSE_DESCRIPTOR);
                registered = hid.registerApp(sdp, null, null, executor, hidCallback);
                refreshStatus(registered ? "Bluetooth-мышь готова к регистрации" : "Телефон не разрешил режим Bluetooth HID");
            }
        }
        @Override public void onServiceDisconnected(int profile) { hid = null; registered = false; refreshStatus("Bluetooth HID отключён"); }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean plugged) {
            registered = plugged;
            refreshStatus(plugged ? "Air Mouse активна" : "Ожидание подключения");
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { target = device; refreshStatus("Подключено: " + safeName(device)); }
            else if (state == BluetoothProfile.STATE_DISCONNECTED) { target = null; refreshStatus("Отключено от телевизора"); }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(12,16,27));
        getWindow().setNavigationBarColor(Color.rgb(12,16,27));
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        buildUi();
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_BT);
        } else startHid();
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(22),dp(20),dp(22),dp(18));
        root.setBackgroundColor(Color.rgb(12,16,27));
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        TextView brand = label("AIR MOUSE", 13, 0xFF8EA7FF); brand.setLetterSpacing(.18f); root.addView(brand);
        TextView title = label("Пульт нового\\nпоколения", 30, Color.WHITE); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); root.addView(title, lp(0,dp(86),0,dp(18)));
        LinearLayout card = new LinearLayout(this); card.setOrientation(1); card.setPadding(dp(16),dp(15),dp(16),dp(15)); card.setBackground(round(0xFF1B2437,20)); root.addView(card,lp(-1,-2,0,dp(16)));
        status = label("Проверяем Bluetooth…",14,0xFFB8C5DC); card.addView(status);
        deviceName = label("Телевизор не подключён",18,Color.WHITE); deviceName.setTypeface(null,Typeface.BOLD); card.addView(deviceName,lp(0,dp(42),0,0));
        Button connect = button("Подключить телевизор  ·  Bluetooth",0xFF607DFF); card.addView(connect,lp(-1,dp(52),0,dp(6))); connect.setOnClickListener(v -> chooseDevice());
        TextView section = label("РЕЖИМ УПРАВЛЕНИЯ",12,0xFF8EA7FF); section.setPadding(0,dp(5),0,dp(10)); root.addView(section);
        LinearLayout modes = new LinearLayout(this); modes.setOrientation(0); root.addView(modes,lp(-1,dp(54),0,dp(12)));
        Button touch = button("Тачпад",0xFF293650), air = button("✦  Air Mouse",0xFF607DFF); modes.addView(touch,new LinearLayout.LayoutParams(0,-1,1)); modes.addView(space(dp(8))); modes.addView(air,new LinearLayout.LayoutParams(0,-1,1));
        mode = label("Веди пальцем по панели ниже",13,0xFFB8C5DC); root.addView(mode,lp(0,dp(28),0,dp(5)));
        TouchSurface pad = new TouchSurface(); pad.setBackground(round(0xFF202B40,24)); root.addView(pad,lp(-1,dp(220),0,dp(12)));
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(0); root.addView(actions,lp(-1,dp(48),0,dp(18)));
        Button left = button("ЛЕВЫЙ КЛИК",0xFF293650), right = button("ПРАВЫЙ КЛИК",0xFF293650);
        actions.addView(left,new LinearLayout.LayoutParams(0,-1,1)); actions.addView(space(dp(8))); actions.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        left.setOnClickListener(v -> { sendMouse(1,0,0,0); sendMouse(0,0,0,0); }); right.setOnClickListener(v -> { sendMouse(2,0,0,0); sendMouse(0,0,0,0); });
        TextView settings = label("НАСТРОЙКИ КУРСОРА",12,0xFF8EA7FF); root.addView(settings,lp(0,dp(35),0,dp(4)));
        addSlider("Чувствительность",1,5,1.4f, value -> sensitivity=value);
        addSlider("Сглаживание",0,0.9f,.35f,value -> smooth=value);
        Button calibrate = button("◎  Калибровать гироскоп",0xFF293650); root.addView(calibrate,lp(-1,dp(48),0,dp(12)));
        calibrate.setOnClickListener(v -> { filteredX=filteredY=0; status.setText("Калибровка выполнена. Держи телефон ровно."); });
        touch.setOnClickListener(v -> setMode(false)); air.setOnClickListener(v -> setMode(true));
    }

    interface ValueChanged { void set(float v); }
    private void addSlider(String name,float min,float max,float initial,ValueChanged changed) {
        TextView t=label(name+"  ·  "+String.format(Locale.US,"%.1f",initial),14,Color.WHITE); root.addView(t);
        SeekBar s=new SeekBar(this); s.setMax(100); s.setProgress((int)((initial-min)/(max-min)*100)); root.addView(s,lp(-1,dp(40),0,dp(6)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar b,int p,boolean u){float v=min+(max-min)*p/100f;changed.set(v);t.setText(name+"  ·  "+String.format(Locale.US,"%.1f",v));} public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} });
    }

    private void setMode(boolean airOn) {
        airMode=airOn;
        if (airMode && gyro!=null) { sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME); mode.setText("Поворачивай телефон — курсор следует за движением"); }
        else { sensors.unregisterListener(this); mode.setText(airMode ? "Гироскоп не найден на устройстве" : "Веди пальцем по панели ниже"); if(gyro==null) airMode=false; }
    }

    private void startHid() {
        if (adapter==null || !adapter.isEnabled()) { refreshStatus("Включи Bluetooth на телефоне"); return; }
        try { boolean requested = adapter.getProfileProxy(this,profileListener,BluetoothProfile.HID_DEVICE); if (!requested) refreshStatus("Телефон не поддерживает Bluetooth HID в режиме мыши"); }
        catch(Exception e) { refreshStatus("Bluetooth HID недоступен на этом телефоне"); }
    }

    private void chooseDevice() {
        if (adapter==null) return;
        try {
            Set<BluetoothDevice> bonded=adapter.getBondedDevices();
            ArrayList<BluetoothDevice> list=new ArrayList<>(bonded);
            if(list.isEmpty()){ refreshStatus("Разреши обнаружение телефона, затем найди его в Bluetooth-меню телевизора"); Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE); discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300); startActivity(discoverable); return; }
            String[] names=new String[list.size()]; for(int i=0;i<list.size();i++) names[i]=safeName(list.get(i));
            new AlertDialog.Builder(this).setTitle("Выбери телевизор").setItems(names,(d,which)->{
                target=list.get(which);
                if(hid!=null) hid.connect(target);
                refreshStatus("Подключаемся к "+safeName(target)+"…");
            }).setNegativeButton("Отмена",null).show();
        } catch(SecurityException e) { refreshStatus("Нужно разрешение Bluetooth"); }
    }

    private void sendMouse(int buttons,int dx,int dy,int wheel) {
        if(hid==null || target==null) return;
        try { hid.sendReport(target,REPORT_ID,new byte[]{(byte)buttons,(byte)clamp(dx),(byte)clamp(dy),(byte)clamp(wheel)}); }
        catch(Exception ignored) {}
    }
    private int clamp(int n){return Math.max(-127,Math.min(127,n));}
    private String safeName(BluetoothDevice d){try{return d.getName()!=null?d.getName():"Bluetooth TV";}catch(Exception e){return "Bluetooth TV";}}
    private void refreshStatus(String s){runOnUiThread(()->{if(status!=null)status.setText(s);if(deviceName!=null && target!=null)deviceName.setText(safeName(target));});}

    @Override public void onSensorChanged(SensorEvent e) {
        if(!airMode || target==null || lastSensor==0){lastSensor=e.timestamp;return;}
        float dt=Math.min(.05f,(e.timestamp-lastSensor)/1_000_000_000f); lastSensor=e.timestamp;
        float x=e.values[1]*dt*sensitivity*95f, y=-e.values[0]*dt*sensitivity*95f;
        filteredX=filteredX*smooth+x*(1-smooth); filteredY=filteredY*smooth+y*(1-smooth);
        int dx=Math.round(filteredX),dy=Math.round(filteredY);
        if(dx!=0||dy!=0){sendMouse(0,dx,dy,0);filteredX-=dx;filteredY-=dy;}
    }
    @Override public void onAccuracyChanged(Sensor s,int a){}
    @Override protected void onPause(){super.onPause();sensors.unregisterListener(this);lastSensor=0;}
    @Override protected void onResume(){super.onResume();if(airMode&&gyro!=null)sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_BT && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED)startHid();else refreshStatus("Без разрешения Bluetooth подключение невозможно");}

    private class TouchSurface extends View {
        float px,py; boolean moving;
        TouchSurface(){super(MainActivity.this);setOnTouchListener((v,e)->{switch(e.getAction()){case MotionEvent.ACTION_DOWN:px=e.getX();py=e.getY();moving=true;return true;case MotionEvent.ACTION_MOVE:if(moving){float dx=(e.getX()-px)*.65f,dy=(e.getY()-py)*.65f;sendMouse(0,Math.round(dx),Math.round(dy),0);px=e.getX();py=e.getY();}return true;case MotionEvent.ACTION_UP:moving=false;performClick();return true;}return true;});}
        @Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);android.graphics.Paint p=new android.graphics.Paint(3);p.setColor(0xFF7186B0);p.setTextAlign(android.graphics.Paint.Align.CENTER);p.setTextSize(dp(14));c.drawText("ПРОВЕДИ ПАЛЬЦЕМ",getWidth()/2f,getHeight()/2f,p);p.setColor(0x337F9FFF);c.drawCircle(getWidth()/2f,getHeight()/2f,dp(35),p);}
        @Override public boolean performClick(){super.performClick();return true;}
    }

    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(round(color,16));return b;}
    private android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private View space(int w){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(w,1));return v;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.leftMargin=l;p.bottomMargin=b;return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}