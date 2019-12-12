package pt.ulisboa.tecnico.cmov.blefingerprinting;

import android.app.Application;
import android.content.Context;

public class GlobalClass extends Application {
    // Activity
    private Context context;

    public GlobalClass() {}

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }

}
