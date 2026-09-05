package app.mnema.identityaccount.transfer;

final class AccountTransferFailure extends RuntimeException {
    private final String code;

    AccountTransferFailure(String code) {
        super(code);
        this.code = code;
    }

    AccountTransferFailure(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
