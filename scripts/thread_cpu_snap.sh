#!/system/bin/sh
# 线程级 CPU 精确采样：schedstat 第 1 字段为累计运行纳秒
# 用法: thread_cpu_snap.sh <pid> <秒数>
P=$1
D=${2:-3}
S=/data/local/tmp

snap() {
  OUT=$1
  : > "$OUT"
  for t in /proc/$P/task/*; do
    tid=${t##*/}
    [ -r "$t/schedstat" ] || continue
    read ns wait slices < "$t/schedstat" 2>/dev/null || continue
    cm=$(cat "$t/comm" 2>/dev/null)
    # 优先级/策略也一并取出，便于判断是否被自抬
    st=$(cat "$t/stat" 2>/dev/null)
    prio=$(echo "$st" | awk '{print $18}')
    pol=$(echo "$st" | awk '{print $41}')
    echo "$tid|$ns|$cm|$prio|$pol" >> "$OUT"
  done
}

snap "$S/tc1.txt"
sleep "$D"
snap "$S/tc2.txt"
echo "SNAP_OK $D"
