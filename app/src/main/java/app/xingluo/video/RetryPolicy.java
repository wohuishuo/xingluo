package app.xingluo.video;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
public final class RetryPolicy {
    public interface Operation<T>{T run(int attempt)throws Exception;}
    public interface Listener{void retry(int nextAttempt,Exception error);}
    public interface Pause{void await(long millis)throws Exception;}
    public static <T>T run(int max,Operation<T> op,BooleanSupplier cancel,Listener listener,Pause pause)throws Exception{
        if(max<1)throw new IllegalArgumentException("尝试次数必须大于零");
        for(int attempt=1;attempt<=max;attempt++){
            if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();
            try{return op.run(attempt);}catch(CancellationException cancelled){throw cancelled;}
            catch(Exception error){if(attempt==max)throw error;listener.retry(attempt+1,error);pause.await(attempt*1000L);}
        }
        throw new IllegalStateException();
    }
}
