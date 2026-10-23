package top.niunaijun.blackbox.fake.frameworks;

import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.ParameterizedType;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * Created by BlackBox on 2022/3/23.
 */
public abstract class BlackManager<Service extends IInterface> {
    public static final String TAG = "BlackManager";

    private Service mService;

    /**
     * 已注册 DeathRecipient 的 binder。用于保证「同一 binder 只 linkToDeath 一次」。
     *
     * 背景：linkToDeath 每次调用都会在 JNI 层 NewWeakGlobalRef(recipient)，
     * 且只有 binder 死亡回调时才释放。即便复用同一个 Java DeathRecipient 实例，
     * 反复 linkToDeath 仍会让 weak global ref 线性增长。
     * Android 16 上 weak global 表上限 51200，反复取服务后直接 JNI abort：
     *   JNI ERROR (app bug): weak global reference table overflow (max=51200)
     * 主进程一 abort 就是用户看到的"闪退"。
     *
     * 修复：缓存已 link 的 binder 引用，同一 binder 不重复 link；换新 binder 时
     * 先 unlink 旧的再 link 新的，把 weak ref 数量限制在「服务实际重建次数」级别。
     */
    private IBinder mLinkedBinder;

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            synchronized (BlackManager.this) {
                // binder 已死，无需（也无法可靠地）显式 unlink，直接清缓存即可。
                // 下次 getService 拿到新 binder 时会重新 link。
                mService = null;
                mLinkedBinder = null;
            }
        }
    };

    protected abstract String getServiceName();

    public Service getService() {
        if (isAlive(mService)) {
            return mService;
        }
        synchronized (this) {
            if (isAlive(mService)) {
                return mService;
            }
            try {
                Service service = Reflector.on(getTClass().getName() + "$Stub")
                        .method("asInterface", IBinder.class)
                        .call(BlackBoxCore.get().getService(getServiceName()));
                if (service == null) {
                    return null;
                }
                IBinder binder = service.asBinder();
                if (mLinkedBinder != binder) {
                    // 换新 binder：先解除对旧 binder 的死亡监听，再对新 binder 注册。
                    if (mLinkedBinder != null) {
                        try {
                            mLinkedBinder.unlinkToDeath(mDeathRecipient, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        binder.linkToDeath(mDeathRecipient, 0);
                        mLinkedBinder = binder;
                    } catch (Throwable ignored) {
                        // binder 已死或注册失败：不缓存，下次重试即可
                    }
                }
                mService = service;
                return service;
            } catch (Throwable e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    private boolean isAlive(Service service) {
        if (service == null) {
            return false;
        }
        try {
            IBinder binder = service.asBinder();
            return binder != null && binder.pingBinder() && binder.isBinderAlive();
        } catch (Throwable e) {
            return false;
        }
    }

    private Class<Service> getTClass() {
        return (Class<Service>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }
}
