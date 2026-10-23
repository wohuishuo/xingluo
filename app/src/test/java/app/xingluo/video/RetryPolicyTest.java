package app.xingluo.video;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryPolicyTest {
    @Test public void resolvesFreshAddressOnEachAttempt() throws Exception {
        AtomicInteger resolves=new AtomicInteger();List<String> tried=new ArrayList<>();
        String result=RetryPolicy.run(3,attempt->{String fresh="cdn-"+resolves.incrementAndGet();tried.add(fresh);if(attempt<3)throw new java.io.IOException("expired");return fresh;},()->false,(next,e)->{},millis->{});
        assertEquals("cdn-3",result);assertEquals(List.of("cdn-1","cdn-2","cdn-3"),tried);
    }
    @Test public void stopsAfterThreeFailures() {
        AtomicInteger attempts=new AtomicInteger();
        assertThrows(java.io.IOException.class,()->RetryPolicy.run(3,n->{attempts.incrementAndGet();throw new java.io.IOException("offline");},()->false,(next,e)->{},millis->{}));
        assertEquals(3,attempts.get());
    }
    @Test public void successDoesNotKeepRetrying() throws Exception {
        AtomicInteger calls=new AtomicInteger();assertEquals("ok",RetryPolicy.run(3,n->{calls.incrementAndGet();return "ok";},()->false,(next,e)->{},millis->{}));assertEquals(1,calls.get());
    }
    @Test public void cancellationNeverRetries() {
        AtomicInteger calls=new AtomicInteger();assertThrows(CancellationException.class,()->RetryPolicy.run(3,n->{calls.incrementAndGet();throw new CancellationException();},()->false,(next,e)->{},millis->{}));assertEquals(1,calls.get());
    }
}
