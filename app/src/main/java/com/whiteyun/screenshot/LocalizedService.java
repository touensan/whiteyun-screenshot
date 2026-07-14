package com.whiteyun.screenshot;

import android.app.Service;
import android.content.Context;
import android.content.res.Resources;

abstract class LocalizedService extends Service {
    private Context systemBaseContext;

    @Override
    protected void attachBaseContext(Context base) {
        systemBaseContext = base;
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override
    public Resources getResources() {
        return systemBaseContext == null
                ? super.getResources()
                : AppLocale.wrap(systemBaseContext).getResources();
    }
}
