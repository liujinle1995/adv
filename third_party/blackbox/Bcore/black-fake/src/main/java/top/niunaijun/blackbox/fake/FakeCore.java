package top.niunaijun.blackbox.fake;

import top.niunaijun.jnihook.ReflectCore;

/**
 * Created by Milk on 2021/5/7.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class FakeCore {
    public static void init() {
        // 修改说明（ADV 移植）：
        // 原代码使用 android.app.ActivityThread.class 字面量，但该类是 @hide，
        // 现代 android.jar（API 34+）不再包含它，编译会失败。
        // 改为运行时 Class.forName，行为等价（ReflectCore.set 只是去掉 final 标志）。
        try {
            ReflectCore.set(Class.forName("android.app.ActivityThread"));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("android.app.ActivityThread not found", e);
        }
    }
}
