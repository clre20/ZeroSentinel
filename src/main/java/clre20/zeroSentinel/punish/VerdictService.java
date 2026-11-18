package clre20.zeroSentinel.punish;

import clre20.zeroSentinel.event.EventId;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.EventRecord;
import clre20.zeroSentinel.event.Verdict;
import clre20.zeroSentinel.trust.TrustManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 判定事件（指令與箱子 UI 共用）。判定為合法時，自動把這個事件扣掉的信用點數退還給玩家（留下修改紀錄）。
 * 回傳的 future 在背景執行緒完成，呼叫端自行切回主執行緒。
 */
public final class VerdictService {

    public record Single(EventRecord event, int refund) {
    }

    public record Bulk(String playerName, int count, int refund) {
    }

    private final EventManager events;
    private final TrustManager trust;

    public VerdictService(EventManager events, TrustManager trust) {
        this.events = events;
        this.trust = trust;
    }

    public CompletableFuture<Optional<Single>> judge(long eventId, Verdict verdict, String by, String note) {
        return events.judge(eventId, verdict, by, note).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Single>empty());
            }
            EventManager.VerdictResult r = opt.get();
            if (r.refund() <= 0) {
                return CompletableFuture.completedFuture(Optional.of(new Single(r.event(), 0)));
            }
            return trust.adminModify(r.event().uuid(), r.event().playerName(), TrustManager.Operation.ADD,
                            r.refund(), by, "誤判退還 " + EventId.format(r.event().id()))
                    .thenApply(t -> Optional.of(new Single(r.event(), r.refund())));
        });
    }

    /** 判定某位玩家所有尚未判定的事件；合法時一次退還全部扣分（一筆修改紀錄） */
    public CompletableFuture<Bulk> judgeAll(UUID uuid, String fallbackName, Verdict verdict, String by, String note) {
        return events.unjudgedEvents(uuid).thenCompose(list -> {
            List<CompletableFuture<Optional<EventManager.VerdictResult>>> futures = new ArrayList<>();
            for (EventRecord e : list) {
                futures.add(events.judge(e.id(), verdict, by, note));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenCompose(v -> {
                int refund = 0;
                int count = 0;
                String name = fallbackName;
                for (CompletableFuture<Optional<EventManager.VerdictResult>> f : futures) {
                    Optional<EventManager.VerdictResult> r = f.join();
                    if (r.isPresent()) {
                        count++;
                        refund += r.get().refund();
                        if (r.get().event().playerName() != null) {
                            name = r.get().event().playerName();
                        }
                    }
                }
                final int c = count;
                final int total = refund;
                final String n = name;
                if (total <= 0) {
                    return CompletableFuture.completedFuture(new Bulk(n, c, 0));
                }
                return trust.adminModify(uuid, n, TrustManager.Operation.ADD, total, by, "誤判退還（批次 " + c + " 個事件）")
                        .thenApply(t -> new Bulk(n, c, total));
            });
        });
    }
}
