package lol.bai.ravel.util;

import com.intellij.platform.util.progress.RawProgressReporter;
import com.intellij.platform.util.progress.StepsKt;
import java.util.function.Consumer;
import kotlin.coroutines.Continuation;

/**
 * Hack to suppress internal API usage errors because inlined functions.
 *
 * @see <a href="https://youtrack.jetbrains.com/issue/MP-7133">MP-7133</a>
 */
@SuppressWarnings("UnstableApiUsage")
public final class NoInline {
    public static void reportRawProgress(Continuation<Object> continuation, Consumer<RawProgressReporter> consumer) {
        StepsKt.reportRawProgress((progress) -> {
            consumer.accept(progress);
            return null;
        }, continuation);
    }
}
