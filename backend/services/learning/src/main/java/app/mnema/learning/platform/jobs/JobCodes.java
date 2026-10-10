package app.mnema.learning.platform.jobs;

import java.util.regex.Pattern;

/** The shape of the short codes stored as {@code last_error} and of queue names. */
final class JobCodes {
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    static final Pattern QUEUE = Pattern.compile("[a-z][a-z0-9_.-]{0,62}");
    static final String HANDLER_ERROR = "handler_error";

    private JobCodes() {
    }

    static String require(String code) {
        if (code == null || !CODE.matcher(code).matches()) throw new IllegalArgumentException("A job error code must match [A-Za-z0-9_.-]{1,64}");
        return code;
    }

    static String queue(String queue) {
        if (queue == null || !QUEUE.matcher(queue).matches()) throw new IllegalArgumentException("A job queue must match [a-z][a-z0-9_.-]{0,62}");
        return queue;
    }
}
