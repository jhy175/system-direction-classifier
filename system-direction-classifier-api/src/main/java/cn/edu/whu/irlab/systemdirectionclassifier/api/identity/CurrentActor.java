package cn.edu.whu.irlab.systemdirectionclassifier.api.identity;
/** Established only by the authenticated HTTP boundary; always cleared after a request. */
public final class CurrentActor {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private CurrentActor() {}
    public static void set(Long userId) { USER.set(userId); }
    public static void clear() { USER.remove(); }
    public static Long id() {
        if (USER.get()==null) throw new IdentityException(401,"请先登录");
        return USER.get();
    }
}

