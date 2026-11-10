package io.romgold.airmouse;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
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
    private boolean airMode = false, appRegistered = false, connecting = false;
    private float sensitivity = 1.8f, smooth = .30f, filteredX, filteredY;
    private long lastSensor;
    private AirMouseView ui;
    private String statusText = "Проверяем Bluetooth…";
    private String deviceText = "Телевизор не подключён";
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
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            try {
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "Air Mouse", "Bluetooth touchpad and gyro mouse", "Romgold",
                    BluetoothHidDevice.SUBCLASS1_MOUSE, MOUSE_DESCRIPTOR);
                boolean ok = hid.registerApp(sdp, null, null, executor, hidCallback);
                setStatus(ok ? "Bluetooth HID зарегистрирован" : "Телефон отказал в регистрации HID");
                if (!ok) setDevice("HID недоступен — проверь поддержку телефона");
            } catch (SecurityException e) {
                setStatus("Нет разрешения на управление Bluetooth");
            } catch (Exception e) {
                setStatus("Ошибка запуска Bluetooth HID: " + e.getMessage());
            }
        }
        @Override public void onServiceDisconnected(int profile) {
            hid = null; appRegistered = false; connecting = false;
            setStatus("Служба Bluetooth HID отключилась");
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            appRegistered = registered;
            setStatus(registered ? "Готово к сопряжению с телевизором" : "Регистрация Bluetooth HID снята");
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                target = device; connecting = false;
                setDevice(safeName(device)); setStatus("Подключено по Bluetooth");
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                target = device; connecting = true; setStatus("Устанавливаем соединение…");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (target != null && target.equals(device)) target = null;
                connecting = false; setDevice("Телевизор не подключён");
                setStatus("Отключено. Нажми «Подключить»");
            }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(7, 11, 22));
        getWindow().setNavigationBarColor(Color.rgb(7, 11, 22));
        getWindow().getDecorView().setSystemUiVisibility(0);
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        ui = new AirMouseView();
        setContentView(ui);
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        ensureBluetoothPermission();
    }

    private void ensureBluetoothPermission() {
        if (adapter == null) { setStatus("В телефоне не найден Bluetooth-адаптер"); return; }
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<String> missing = new ArrayList<>();
            for (String p : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE})
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), REQ_BT); return; }
        }
        startHid();
    }

    private void startHid() {
        if (adapter == null) { setStatus("Bluetooth-адаптер не найден"); return; }
        try {
            if (!adapter.isEnabled()) { setStatus("Сначала включи Bluetooth в настройках телефона"); return; }
            boolean requested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE);
            if (!requested) {
                setStatus("Android не предоставил профиль HID Device");
                setDevice("Возможно, прошивка Tank 2 не поддерживает Bluetooth-мышь");
            } else setStatus("Запрашиваем профиль Bluetooth HID…");
        } catch (SecurityException e) {
            setStatus("Не выданы разрешения Bluetooth");
        } catch (Exception e) {
            setStatus("Bluetooth HID не поддерживается: " + e.getMessage());
        }
    }

    private void connectAction() {
        if (adapter == null) { setStatus("Bluetooth-адаптер не найден"); return; }
        try {
            if (!adapter.isEnabled()) {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                setStatus("Включи Bluetooth и вернись в приложение");
                return;
            }
            if (hid == null || !appRegistered) {
                setStatus("Bluetooth HID ещё не готов. Проверяем профиль…");
                startHid();
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            ArrayList<BluetoothDevice> devices = new ArrayList<>(bonded);
            if (devices.isEmpty()) {
                setStatus("Сначала выполни сопряжение на телевизоре");
                new AlertDialog.Builder(this)
                    .setTitle("Сопряжение с телевизором")
                    .setMessage("1. Нажми «Сделать телефон видимым».\n2. На телевизоре открой Настройки → Пульты и аксессуары → Добавить устройство.\n3. Выбери «Air Mouse» или имя телефона и подтверди сопряжение.\n4. Вернись сюда и нажми «Подключить» ещё раз.")
                    .setPositiveButton("Сделать телефон видимым", (d,w) -> makeDiscoverable())
                    .setNegativeButton("Позже", null).show();
                return;
            }
            ArrayList<BluetoothDevice> candidates = new ArrayList<>();
            for (BluetoothDevice d : devices) {
                int type = d.getType();
                if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC || type == BluetoothDevice.DEVICE_TYPE_DUAL || type == BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                    candidates.add(d);
            }
            if (candidates.isEmpty()) candidates = devices;
            String[] names = new String[candidates.size()];
            for (int i=0;i<candidates.size();i++) names[i] = safeName(candidates.get(i));
            final ArrayList<BluetoothDevice> choices = candidates;
            new AlertDialog.Builder(this).setTitle("Выбери сопряжённое устройство")
                .setItems(names, (dialog, which) -> connectTo(choices.get(which)))
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Новое сопряжение", (dialog, which) -> makeDiscoverable()).show();
        } catch (SecurityException e) {
            setStatus("Android запретил доступ к списку Bluetooth-устройств");
        }
    }

    private void makeDiscoverable() {
        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(intent, 42);
            setStatus("Разреши обнаружение телефона, затем найди его на ТВ");
        } catch (Exception e) { setStatus("Не удалось включить обнаружение: " + e.getMessage()); }
    }

    private void connectTo(BluetoothDevice device) {
        if (hid == null || !appRegistered) { setStatus("Профиль Bluetooth HID не зарегистрирован"); return; }
        try {
            target = device; connecting = true;
            setDevice(safeName(device)); setStatus("Отправляем запрос подключения…");
            boolean accepted = hid.connect(device);
            if (!accepted) {
                connecting = false;
                setStatus("Телефон отклонил запрос HID. Возможно, ТВ не поддерживает HID-мышь");
            } else setStatus("Запрос отправлен. Ожидаем подтверждение телевизора…");
        } catch (SecurityException e) {
            connecting = false; setStatus("Нет разрешения Bluetooth CONNECT");
        } catch (Exception e) {
            connecting = false; setStatus("Не удалось подключиться: " + e.getMessage());
        }
    }

    private void sendMouse(int buttons, int dx, int dy, int wheel) {
        if (hid == null || target == null || !appRegistered) return;
        try {
            hid.sendReport(target, REPORT_ID, new byte[]{(byte)buttons,(byte)clamp(dx),(byte)clamp(dy),(byte)clamp(wheel)});
        } catch (Exception e) { setStatus("Ошибка отправки команды мыши"); }
    }
    private void click(int button) { sendMouse(button,0,0,0); sendMouse(0,0,0,0); }
    private int clamp(int n) { return Math.max(-127, Math.min(127,n)); }
    private String safeName(BluetoothDevice d) { try { String n=d.getName(); return n==null?"Bluetooth-устройство":n; } catch(Exception e){return "Bluetooth-устройство";} }
    private void setStatus(String s) { statusText=s; runOnUiThread(() -> { if(ui!=null) ui.invalidate(); }); }
    private void setDevice(String s) { deviceText=s; runOnUiThread(() -> { if(ui!=null) ui.invalidate(); }); }

    private void setMode(boolean air) {
        airMode=air; lastSensor=0; filteredX=filteredY=0;
        if (air && gyro != null) {
            sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME);
            setStatus("Air Mouse включена — плавно поворачивай телефон");
        } else {
            sensors.unregisterListener(this);
            if (air) { airMode=false; setStatus("В телефоне не найден гироскоп"); }
            else setStatus("Тачпад включён — веди пальцем по панели");
        }
        if(ui!=null) ui.invalidate();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(1); box.setPadding(28,12,28,4);
        TextView a = new TextView(this); a.setText("Чувствительность"); box.addView(a);
        SeekBar sens = new SeekBar(this); sens.setMax(40); sens.setProgress(Math.round((sensitivity-0.5f)*10)); box.addView(sens);
        TextView b = new TextView(this); b.setText("Сглаживание"); box.addView(b);
        SeekBar sm = new SeekBar(this); sm.setMax(90); sm.setProgress(Math.round(smooth*100)); box.addView(sm);
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean u){sensitivity=.5f+p/10f;}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        sm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean u){smooth=p/100f;}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        new AlertDialog.Builder(this).setTitle("Настройки курсора").setView(box).setPositiveButton("Готово",null).setNeutralButton("Калибровка",(d,w)->{filteredX=filteredY=0;lastSensor=0;setStatus("Гироскоп откалиброван — держи телефон неподвижно");}).show();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (!airMode || target==null || lastSensor==0) { lastSensor=e.timestamp; return; }
        float dt=Math.min(.05f,(e.timestamp-lastSensor)/1_000_000_000f); lastSensor=e.timestamp;
        float x=e.values[1]*dt*sensitivity*115f, y=-e.values[0]*dt*sensitivity*115f;
        filteredX=filteredX*smooth+x*(1-smooth); filteredY=filteredY*smooth+y*(1-smooth);
        int dx=Math.round(filteredX),dy=Math.round(filteredY);
        if(dx!=0||dy!=0){sendMouse(0,dx,dy,0);filteredX-=dx;filteredY-=dy;}
    }
    @Override public void onAccuracyChanged(Sensor s,int a) {}
    @Override protected void onPause(){super.onPause();if(sensors!=null)sensors.unregisterListener(this);lastSensor=0;}
    @Override protected void onResume(){super.onResume();if(airMode&&gyro!=null)sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_BT) {
            boolean ok=true; for(int result:g) if(result!=PackageManager.PERMISSION_GRANTED) ok=false;
            if(ok) startHid(); else setStatus("Нужно разрешить доступ к Bluetooth в настройках приложения");
        }
    }

    private class AirMouseView extends View {
        private final Paint p=new Paint(3);
        private final float density=getResources().getDisplayMetrics().density;
        private float downX,downY,lastX,lastY;
        private boolean padGesture=false, moved=false;
        private final RectF r=new RectF();
        AirMouseView(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setFocusable(true);}
        private int c(String color){return Color.parseColor(color);}
        private void rounded(Canvas canvas,float l,float t,float rr,float b,float rad,int top,int bottom) {
            r.set(l,t,rr,b); p.setShader(new LinearGradient(l,t,l,b,top,bottom,Shader.TileMode.CLAMP)); p.setStyle(Paint.Style.FILL);
            p.setShadowLayer(13,0,7,0x99000000); canvas.drawRoundRect(r,rad,rad,p); p.clearShadowLayer(); p.setShader(null);
        }
        private void text(Canvas canvas,String s,float x,float y,float size,int color,boolean bold) {
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL));p.setTextAlign(Paint.Align.LEFT);canvas.drawText(s,x,y,p);
        }
        private void centered(Canvas canvas,String s,float x,float y,float size,int color,boolean bold) {
            p.setTextAlign(Paint.Align.CENTER);p.setColor(color);p.setTextSize(size);p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL));canvas.drawText(s,x,y,p);p.setTextAlign(Paint.Align.LEFT);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float sx=getWidth()/390f, sy=getHeight()/844f;
            canvas.save();canvas.scale(sx,sy);
            p.setShader(new LinearGradient(0,0,390,844,c("#111C35"),c("#070B16"),Shader.TileMode.CLAMP));canvas.drawRect(0,0,390,844,p);p.setShader(null);
            // Ambient 3D glow
            p.setColor(0x223E69FF);p.setMaskFilter(new BlurMaskFilter(55,BlurMaskFilter.Blur.NORMAL));canvas.drawCircle(330,180,105,p);p.setMaskFilter(null);
            text(canvas,"ROMGOLD  /  WIRELESS CONTROL",22,38,10,c("#8EAFFF"),true);
            text(canvas,"Air Mouse",22,82,31,Color.WHITE,true);
            text(canvas,"Твой телефон — пульт для телевизора",23,106,12,c("#9AAAC7"),false);

            rounded(canvas,20,124,370,239,20,c("#263A61"),c("#17243D"));
            text(canvas,"BLUETOOTH  •  HID MOUSE",37,150,10,c("#91B3FF"),true);
            text(canvas,deviceText.length()>31?deviceText.substring(0,29)+"…":deviceText,37,176,17,Color.WHITE,true);
            text(canvas,statusText.length()>44?statusText.substring(0,42)+"…":statusText,37,195,10,c("#B6C6E5"),false);
            rounded(canvas,30,205,360,230,12,c("#628CFF"),c("#3156C5"));
            centered(canvas,connecting?"ПОДКЛЮЧАЕМ…":"ПОДКЛЮЧИТЬ ТЕЛЕВИЗОР",195,222,11,Color.WHITE,true);

            text(canvas,"РЕЖИМ УПРАВЛЕНИЯ",22,267,10,c("#8EAFFF"),true);
            rounded(canvas,20,280,190,329,14,airMode?c("#202E4B"):c("#557EFF"),airMode?c("#17243B"):c("#3156C5"));
            rounded(canvas,200,280,370,329,14,airMode?c("#557EFF"):c("#202E4B"),airMode?c("#3156C5"):c("#17243B"));
            centered(canvas,"Тачпад",105,310,14,Color.WHITE,true);
            centered(canvas,"✦  Air Mouse",285,310,14,Color.WHITE,true);

            text(canvas,airMode?"ПОВОРАЧИВАЙ ТЕЛЕФОН":"СЕНСОРНАЯ ПАНЕЛЬ",22,355,10,c("#8EAFFF"),true);
            rounded(canvas,20,369,370,646,25,c("#263B60"),c("#111D32"));
            // glossy inset surface
            r.set(27,376,363,639);p.setColor(0x22FFFFFF);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);canvas.drawRoundRect(r,20,20,p);p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0,380,0,640,0x1FFFFFFF,0x001FFFFFFF,Shader.TileMode.CLAMP));canvas.drawRoundRect(28,377,362,638,20,20,p);p.setShader(null);
            p.setColor(0x334F80FF);p.setMaskFilter(new BlurMaskFilter(30,BlurMaskFilter.Blur.NORMAL));canvas.drawCircle(195,505,50,p);p.setMaskFilter(null);
            centered(canvas,airMode?"GYROSCOPE ACTIVE":"ПРОВЕДИ ПАЛЬЦЕМ",195,495,13,c("#D5E2FF"),true);
            centered(canvas,airMode?"Двигай телефоном для управления":"Нажатие на панель — левый клик",195,519,10,c("#8FA4CA"),false);
            centered(canvas,"",195,0,1,Color.WHITE,false);

            rounded(canvas,20,663,190,723,16,c("#31466D"),c("#1B2944"));
            rounded(canvas,200,663,370,723,16,c("#31466D"),c("#1B2944"));
            centered(canvas,"ЛЕВЫЙ КЛИК",105,699,12,Color.WHITE,true);
            centered(canvas,"ПРАВЫЙ КЛИК",285,699,12,Color.WHITE,true);
            rounded(canvas,20,742,190,802,15,c("#243754"),c("#15233B"));
            rounded(canvas,200,742,370,802,15,c("#243754"),c("#15233B"));
            centered(canvas,"◎  КАЛИБРОВКА",105,778,11,c("#D8E4FF"),true);
            centered(canvas,"⚙  НАСТРОЙКИ",285,778,11,c("#D8E4FF"),true);
            centered(canvas,"AIR MOUSE  •  BLUETOOTH HID",195,827,9,c("#52678F"),true);
            canvas.restore();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float x=e.getX()/(getWidth()/390f), y=e.getY()/(getHeight()/844f);
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX=lastX=x;downY=lastY=y;moved=false;padGesture=(x>=20&&x<=370&&y>=369&&y<=646);return true;
                case MotionEvent.ACTION_MOVE:
                    if(padGesture && !airMode && target!=null) {
                        float dx=(x-lastX)*.78f,dy=(y-lastY)*.78f;
                        if(Math.abs(x-downX)+Math.abs(y-downY)>5)moved=true;
                        int ix=Math.round(dx),iy=Math.round(dy);
                        if(ix!=0||iy!=0)sendMouse(0,ix,iy,0);
                    }
                    lastX=x;lastY=y;return true;
                case MotionEvent.ACTION_UP:
                    if(padGesture) {
                        if(!moved && Math.abs(x-downX)+Math.abs(y-downY)<18) click(1);
                    } else if(y>=190&&y<=240&&x>=20&&x<=370) connectAction();
                    else if(y>=280&&y<=330) {if(x<195)setMode(false);else setMode(true);}
                    else if(y>=663&&y<=725) {if(x<195)click(1);else click(2);}
                    else if(y>=742&&y<=810) {if(x<195){filteredX=filteredY=0;lastSensor=0;setStatus("Калибровка выполнена");}else showSettings();}
                    performClick();padGesture=false;return true;
                case MotionEvent.ACTION_CANCEL:padGesture=false;return true;
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}