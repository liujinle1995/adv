package top.niunaijun.blackbox.fake.hook;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.util.Map;

import black.android.os.BRServiceManager;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public abstract class BinderInvocationStub extends ClassInvocationStub implements IBinder {
    private IBinder mBaseBinder;

    public BinderInvocationStub(IBinder baseBinder) {
        mBaseBinder = baseBinder;
    }

    @Override
    protected void onBindMethod() {
    }

    @Nullable
    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return mBaseBinder.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return mBaseBinder.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return mBaseBinder.isBinderAlive();
    }

    @Nullable
    @Override
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return (IInterface) getProxyInvocation();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dump(fd, args);
    }

    @Override
    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dumpAsync(fd, args);
    }

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        return mBaseBinder.transact(code, data, reply, flags);
    }

    /**
     * Android 14+ 的厂商扩展入口。
     *
     * ColorOS 的 `ActivityThread.performLaunchActivity` 会顺带调用
     * `OplusMultiAppManager.getMultiAppBadgeResourceId()`（应用分身角标），
     * 它通过 `IBinder.getExtension()` 取厂商扩展 binder。`IBinder` 的默认实现直接抛
     *   IllegalStateException: Method is not implemented
     * 而代理没有转发这个方法，于是虚拟进程里 Activity 一启动就崩：
     *   Unable to start activity ComponentInfo{...ProxyActivity$P0}: Method is not implemented
     * 这里把调用转发给被代理的真实 binder。
     */
    // 注意：getExtension() 在公开 android.jar 里不可见（@SystemApi），所以不能加 @Override。
    // 但运行时同名同签名的实例方法会覆盖 IBinder 接口的 default 实现，invokeinterface 能命中。
    public IBinder getExtension() {
        try {
            Method method = IBinder.class.getMethod("getExtension");
            return (IBinder) method.invoke(mBaseBinder);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) throws RemoteException {
        mBaseBinder.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return mBaseBinder.unlinkToDeath(recipient, flags);
    }


    protected void replaceSystemService(String name) {
        Map<String, IBinder> services = BRServiceManager.get().sCache();
        services.put(name, this);
    }
}
