package com.htc.widget;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

/** Compile-time stub; the real class ships in /system/framework/HTCCommonctrl.jar. */
public class HtcAlertDialog extends android.app.Dialog {
    protected HtcAlertDialog(Context c) { super(c); }

    public static class Builder {
        public Builder(Context c) {}
        public Builder setTitle(CharSequence t) { return this; }
        public Builder setMessage(CharSequence m) { return this; }
        public Builder setView(View v) { return this; }
        public Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) { return this; }
        public Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) { return this; }
        public HtcAlertDialog create() { return null; }
        public HtcAlertDialog show() { return null; }
    }
}
