package app.mnema.identityaccount.contract;

public final class AccountFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public AccountFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AccountFailure denied() {
        return new AccountFailure(401, "authentication_failed");
    }

    public static AccountFailure forbidden() {
        return new AccountFailure(403, "operation_denied");
    }
}
