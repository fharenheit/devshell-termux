package com.termux.app.terminal.sftp;

import android.content.Context;
import android.os.Looper;

import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SftpTransfersTest {

    private SftpTransfers mTransfers;
    private final List<SftpTransfers.Transfer> mFinished = new ArrayList<>();
    private final SftpTransfers.Listener mListener = new SftpTransfers.Listener() {
        @Override
        public void onTransfersChanged() {
        }

        @Override
        public void onTransferFinished(SftpTransfers.Transfer transfer) {
            mFinished.add(transfer);
        }
    };

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.application;
        mTransfers = SftpTransfers.get(context);
        mTransfers.setClientFactory(transfer ->
            new SftpClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), () -> {}));
        mTransfers.addListener(mListener);
    }

    @After
    public void tearDown() {
        mTransfers.removeListener(mListener);
        mTransfers.cancelAll();
        mTransfers.clearFinished();
        mTransfers.setClientFactory(null);
    }

    private SftpTransfers.Transfer enqueue(String title, SftpTransfers.Job job) {
        return mTransfers.enqueue("host", null, title, 10, null, Collections.emptyList(), job);
    }

    /** Run the main looper until {@code count} transfers finished. */
    private void awaitFinished(int count) throws InterruptedException {
        for (int i = 0; i < 500 && mFinished.size() < count; i++) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        Assert.assertEquals(count, mFinished.size());
    }

    @Test
    public void testRunsInOrderWithResults() throws InterruptedException {
        SftpTransfers.Transfer first = enqueue("first", (context, client, transfer) -> {
            mTransfers.progress(transfer, 10, 10);
            return "first done";
        });
        SftpTransfers.Transfer second = enqueue("second", (context, client, transfer) -> {
            throw new IOException("broken");
        });
        Assert.assertEquals(2, mTransfers.getActiveCount());
        awaitFinished(2);
        Assert.assertSame(first, mFinished.get(0));
        Assert.assertEquals(SftpTransfers.DONE, first.getState());
        Assert.assertEquals("first done", first.getMessage());
        Assert.assertEquals(10, first.getDone());
        Assert.assertEquals(SftpTransfers.FAILED, second.getState());
        Assert.assertEquals("broken", second.getMessage());
        Assert.assertEquals(0, mTransfers.getActiveCount());

        mTransfers.clearFinished();
        Assert.assertTrue(mTransfers.getTransfers().isEmpty());
    }

    @Test
    public void testCancel() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        SftpTransfers.Transfer running = enqueue("running", (context, client, transfer) -> {
            started.countDown();
            while (!transfer.cancelled.get()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    break;
                }
            }
            throw new InterruptedIOException("Cancelled");
        });
        SftpTransfers.Transfer queued = enqueue("queued", (context, client, transfer) -> "not run");
        Assert.assertTrue(started.await(5, TimeUnit.SECONDS));
        // A queued transfer is cancelled at once and never runs.
        mTransfers.cancel(queued);
        Assert.assertEquals(SftpTransfers.CANCELLED, queued.getState());
        mTransfers.cancel(running);
        awaitFinished(2);
        Assert.assertEquals(SftpTransfers.CANCELLED, running.getState());
        Assert.assertEquals(SftpTransfers.CANCELLED, queued.getState());
        Assert.assertNull(queued.getMessage());
    }

}
