@JavascriptInterface
public void openApp(String appName) {
    activity.runOnUiThread(() -> {
        try {
            if (appName == null || appName.trim().isEmpty()) return;

            String wanted = appName.trim().toLowerCase(Locale.ROOT);
            PackageManager pm = activity.getPackageManager();

            // الخرائط: العربية والفرنسية والإنجليزية
            String[] mapAliases = {
                "خرائط", "الخريطة", "الخرائط",
                "maps", "map", "google maps", "cartes"
            };

            for (String alias : mapAliases) {
                if (wanted.contains(alias)) {
                    Intent mapsIntent = new Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("geo:0,0")
                    );

                    if (mapsIntent.resolveActivity(pm) != null) {
                        activity.startActivity(mapsIntent);
                    } else {
                        Toast.makeText(activity,
                            "لا يوجد تطبيق خرائط متاح",
                            Toast.LENGTH_LONG).show();
                    }
                    return;
                }
            }

            // أسماء شائعة للتطبيقات
            String[][] aliases = {
                {"youtube", "يوتيوب"},
                {"chrome", "كروم"},
                {"whatsapp", "واتساب"},
                {"facebook", "فيسبوك"},
                {"camera", "الكاميرا"},
                {"settings", "الإعدادات"}
            };

            String[] packages = {
                "com.google.android.youtube",
                "com.android.chrome",
                "com.whatsapp",
                "com.facebook.katana",
                "com.android.camera",
                "com.android.settings"
            };

            for (int i = 0; i < aliases.length; i++) {
                for (String alias : aliases[i]) {
                    if (wanted.contains(alias)) {
                        Intent launch =
                            pm.getLaunchIntentForPackage(packages[i]);

                        if (launch != null) {
                            activity.startActivity(launch);
                            return;
                        }
                    }
                }
            }

            // البحث في أسماء التطبيقات الظاهرة في الجهاز
            Intent query = new Intent(Intent.ACTION_MAIN);
            query.addCategory(Intent.CATEGORY_LAUNCHER);

            for (android.content.pm.ResolveInfo info :
                    pm.queryIntentActivities(query, 0)) {

                String label = info.loadLabel(pm)
                    .toString().toLowerCase(Locale.ROOT);

                if (label.contains(wanted)) {
                    Intent launch = pm.getLaunchIntentForPackage(
                        info.activityInfo.packageName
                    );

                    if (launch != null) {
                        activity.startActivity(launch);
                        return;
                    }
                }
            }

            Toast.makeText(activity,
                "لم أجد التطبيق: " + appName,
                Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(activity,
                "تعذر فتح التطبيق",
                Toast.LENGTH_LONG).show();
        }
    });
}
