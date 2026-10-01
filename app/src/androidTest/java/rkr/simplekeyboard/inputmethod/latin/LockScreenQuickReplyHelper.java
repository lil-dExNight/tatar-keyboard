/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Posts a message notification with an inline reply action, for checking the keyboard in the
 * lock-screen quick reply by hand, and logs the text that comes back (tag {@code TtQuickReply}).
 * It acts only under {@link QuickReplySelfRunner} (the test package must own the notification)
 * and returns at once under the regular runner. Before the run: {@code pm grant <test package>
 * android.permission.POST_NOTIFICATIONS}. The wait for the reply is {@code -e ttReplyWaitSeconds}.
 */
public final class LockScreenQuickReplyHelper extends InstrumentationTestCase {
    private static final String TAG = "TtQuickReply";
    private static final String CHANNEL = "tt_quick_reply_check";
    private static final String ACTION_REPLY = "org.tatarkeyboard.ime.test.QUICK_REPLY";
    private static final String RESULT_KEY = "reply_text";
    private static final int NOTIFICATION_ID = 4711;
    private static final String ARG_WAIT = "ttReplyWaitSeconds";
    private static final long DEFAULT_WAIT_SECONDS = 300L;

    public void testPostReplyNotificationAndWaitForReply() throws InterruptedException {
        final Context context = getInstrumentation().getContext();
        if (!context.getPackageName().equals(
                getInstrumentation().getTargetContext().getPackageName())) {
            Log.i(TAG, "skipped: runs only under QuickReplySelfRunner");
            return;
        }
        long waitSeconds = DEFAULT_WAIT_SECONDS;
        if (getInstrumentation() instanceof InstrumentationTestRunner) {
            final Bundle arguments = ((InstrumentationTestRunner) getInstrumentation()).getArguments();
            final String value = arguments == null ? null : arguments.getString(ARG_WAIT);
            if (value != null) {
                waitSeconds = Long.parseLong(value);
            }
        }
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Quick reply check", NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
        assertTrue("notifications are blocked for the test package",
                manager.areNotificationsEnabled());

        final CountDownLatch replied = new CountDownLatch(1);
        final CharSequence[] replyText = new CharSequence[1];
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context receiverContext, final Intent intent) {
                final Bundle results = RemoteInput.getResultsFromIntent(intent);
                replyText[0] = results == null ? null : results.getCharSequence(RESULT_KEY);
                replied.countDown();
            }
        };
        final IntentFilter filter = new IntentFilter(ACTION_REPLY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        try {
            final PendingIntent replyIntent = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_REPLY).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            final Notification.Action action = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_send, "Reply", replyIntent)
                    .addRemoteInput(new RemoteInput.Builder(RESULT_KEY).setLabel("Reply").build())
                    .build();
            final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, CHANNEL)
                    : new Notification.Builder(context);
            final Notification notification = builder
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentTitle("Quick reply check")
                    .setContentText("Reply to this message with the keyboard under test")
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .addAction(action)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
            Log.i(TAG, "posted; waiting up to " + waitSeconds + " s for a reply");
            final boolean gotReply = replied.await(waitSeconds, TimeUnit.SECONDS);
            Log.i(TAG, gotReply ? "reply=[" + replyText[0] + "]" : "no reply");
            assertTrue("no reply within " + waitSeconds + " s", gotReply);
        } finally {
            manager.cancel(NOTIFICATION_ID);
            context.unregisterReceiver(receiver);
        }
    }
}
