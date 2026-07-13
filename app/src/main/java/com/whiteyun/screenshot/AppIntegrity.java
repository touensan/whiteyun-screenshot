package com.whiteyun.screenshot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;

final class AppIntegrity {
    private static final String OFFICIAL_CERT_SHA256 =
            "bb06aa6eb28b356fffb6a363624f1789d24684a68477bc764026daa0717f030e";

    private AppIntegrity() {
    }

    static boolean hasOfficialSignature(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) {
                return false;
            }
            Signature[] signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            if (signatures == null) {
                return false;
            }
            for (Signature signature : signatures) {
                if (OFFICIAL_CERT_SHA256.equals(sha256(signature.toByteArray()))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Public builds fail closed; debug builds skip this check in the Application.
        }
        return false;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            output.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        }
        return output.toString();
    }
}
