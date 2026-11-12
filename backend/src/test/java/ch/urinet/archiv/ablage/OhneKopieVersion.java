package ch.urinet.archiv.ablage;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * Bildet Ceph RGW 20.x nach (tracker.ceph.com/issues/77796): Die Antwort von CopyObject kommt ohne
 * {@code x-amz-version-id}. Mit {@code fremdesEtag} meldet HeadObject zudem ein anderes ETag, als hätte inzwischen
 * jemand anderes unter dem Ziel geschrieben. Nur für Tests.
 */
final class OhneKopieVersion implements ExecutionInterceptor {

    private final boolean fremdesEtag;

    OhneKopieVersion(boolean fremdesEtag) {
        this.fremdesEtag = fremdesEtag;
    }

    @Override
    public SdkHttpResponse modifyHttpResponse(Context.ModifyHttpResponse kontext, ExecutionAttributes attribute) {
        SdkHttpResponse antwort = kontext.httpResponse();
        if (kontext.request() instanceof CopyObjectRequest) {
            var ohne = antwort.toBuilder();
            antwort.headers().keySet().stream().filter("x-amz-version-id"::equalsIgnoreCase).toList()
                    .forEach(ohne::removeHeader);
            return ohne.build();
        }
        if (fremdesEtag && kontext.request() instanceof HeadObjectRequest) {
            var anders = antwort.toBuilder();
            antwort.headers().keySet().stream().filter("ETag"::equalsIgnoreCase).toList().forEach(anders::removeHeader);
            return anders.putHeader("ETag", "\"00000000000000000000000000000000\"").build();
        }
        return antwort;
    }
}
