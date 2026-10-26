package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final String DESCRIPTOR = "android.app.IServiceConnection";
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        if (BuildCompat.isOreo()) {
            BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
        } else {
            mConn.connected(name, service);
        }
    }

    /**
     * Android 17（SDK 37）给 IServiceConnection 的 AIDL 新增了
     * `connected(ComponentName, IBinder, IBinderSession, boolean)`。
     * 生成代码里的 onTransact 会直接调用这个新方法，而 IBinderSession 是 @hide，
     * 编译期 android.jar 里没有，无法声明对应的 override —— 结果就是系统每次回调
     * 都抛 AbstractMethodError，把整个（沙箱）进程崩掉。
     *
     * 兜底：先交给父类；一旦它因缺少实现抛 AbstractMethodError，就把 Parcel 读位置
     * 还原，自己解析前两个参数（ComponentName + IBinder）后转发，多出来的
     * IBinderSession 参数忽略。
     */
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        int start = data.dataPosition();
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (AbstractMethodError e) {
            return handleNewAidlMethod(code, data, reply, start);
        }
    }

    private boolean handleNewAidlMethod(int code, Parcel data, Parcel reply, int start) {
        try {
            data.setDataPosition(start);
            data.enforceInterface(DESCRIPTOR);
            ComponentName name = readComponentName(data);
            IBinder service = data.readStrongBinder();
            top.niunaijun.blackbox.utils.DiagLog.log("Svc",
                    "onTransact fallback code=" + code + " component=" + name
                            + " binder=" + (service != null));
            connected(name, service, false);
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Svc",
                    "onTransact fallback failed: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
            return true;
        }
    }

    /** 新版本 AIDL 用 writeTypedObject 写 ComponentName；老版本是 writeParcelable。 */
    private static ComponentName readComponentName(Parcel data) {
        try {
            java.lang.reflect.Method m = Parcel.class.getMethod(
                    "readTypedObject", android.os.Parcelable.Creator.class);
            return (ComponentName) m.invoke(data, ComponentName.CREATOR);
        } catch (Throwable ignored) {
        }
        try {
            return (ComponentName) data.readParcelable(ComponentName.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
