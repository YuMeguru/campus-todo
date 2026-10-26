package dev.campus.todo;

/**
 * 纯数据结构。本文件不引用任何 Android API，也不引用 org.json，
 * 因此可以直接在 JVM 上被单元测试使用。
 */
public final class Model {

    private Model() {
    }

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DONE = "done";
    /** 信息不足，需要人确认：不自动建提醒。 */
    public static final String STATUS_PENDING = "pending";
    /** 班级通知一类的参考信息：既不是待办，也不是「待确认」，单独分区存放。 */
    public static final String STATUS_INFO = "info";
    /**
     * 已归档：完成或过期满一天后从列表里收起来。
     *
     * 归档**不是删除** —— 条目原样留在库里，随时能恢复。真正删除只有用户手动点「清除」。
     */
    public static final String STATUS_ARCHIVED = "archived";
    /** 模型给出的 kind，表示这是给人回头查的资料而不是要做的事。 */
    public static final String KIND_INFO = "info";

    /** 设备回传的操作（campus-sync/1 的 OP 帧载荷）。 */
    public static final class DeviceOp {
        public static final String DONE = "done";
        public static final String UNDONE = "undone";
        public static final String SNOOZE = "snooze";

        public long todoId;
        /** done | undone | snooze */
        public String action = "";
        public int snoozeMinutes;
        /** 设备侧自增序号，用来回 ACK 与去重。 */
        public int devSeq;
    }

    /** 采集规则。黑名单优先；白名单非空时只放行命中项。 */
    public static final class Rule {
        public static final String ALLOW = "allow";
        public static final String DENY = "deny";

        public long id;
        /** allow | deny */
        public String mode = DENY;
        /** 会话名关键词（群名/联系人名），归一化后做子串匹配。 */
        public String text = "";
    }

    /** 一条采集到的原始通知。 */
    public static final class Raw {
        public long id;
        public String pkg = "";
        public String pkgLabel = "";
        public String title = "";
        public String body = "";
        public long postedAt;
        /** new | done | skip */
        public String state = "new";
        /** auto | share | paste | sample */
        public String ingest = "auto";

        public String display() {
            String t = title == null ? "" : title.trim();
            String b = body == null ? "" : body.trim();
            if (t.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return t;
            }
            return t + "：" + b;
        }
    }

    /** 一条提炼出来的待办。 */
    public static final class Todo {
        public long id;
        public String title = "";
        public String course = "";
        /** homework | exam | notice | other */
        public String kind = "other";
        public String source = "";
        /** 会话名（群名或联系人名）。待办里用不到，但班级通知必须知道是哪个群发的。 */
        public String convo = "";
        /** 来自本批第几条通知（1 起）；0 表示模型没给。 */
        public int srcIndex;
        /** 原文里的时间表述，原样保留。 */
        public String dueText = "";
        /** 绝对截止时间；0 表示不确定。 */
        public long dueAt;
        /**
         * 「稍后提醒」只推迟提醒，不动 dueAt。
         * > now 时用它替代本轮的截止提醒；截止时间本身永远保持原样。
         */
        public long snoozeUntil;
        /** 必须来自原文的片段，用于防幻觉。 */
        public String evidence = "";
        public double confidence;
        public String status = STATUS_ACTIVE;
        public long createdAt;
        /** 标记完成的时刻；0 表示没完成过。归档靠它算「完成满一天」。 */
        public long completedAt;
        /**
         * 归档时刻。
         *
         * 0 = 从没归档过；正数是归档时间；-1 是「用户手动恢复过」的哨兵 ——
         * 恢复回来的过期条目不该隔一天又被自动收走，所以用它把自动归档挡在门外。
         */
        public long archivedAt;

        /** 用户手动恢复过的标记，见 archivedAt 的说明。 */
        public static final long RESTORED = -1;

        public boolean isOpen() {
            return STATUS_ACTIVE.equals(status);
        }

        public boolean isPending() {
            return STATUS_PENDING.equals(status);
        }

        /** 参考信息（班级通知等）：不排提醒、不进待办列表。 */
        public boolean isInfo() {
            return STATUS_INFO.equals(status);
        }

        /**
         * 编辑之后该是什么状态。
         *
         * 「待确认」唯一的出口就在这里：给了确定时间就升级成正式待办（于是开始提醒），
         * 仍然没时间就继续留在待确认。其余状态不因为一次编辑而改变 ——
         * 尤其已完成的不会因为改了个错别字就被「复活」。
         *
         * 放在模型上而不是埋在 Activity 里，是为了它能被单测钉住（见 TodoStateTest）。
         */
        public String statusAfterEdit(long newDueAt) {
            if (isPending()) {
                return newDueAt > 0 ? STATUS_ACTIVE : STATUS_PENDING;
            }
            return status;
        }
    }
}
