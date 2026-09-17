// Outcome callback for a submitted query (osp_lite_v05.html §5).
package com.swarmknowledge.ospbridge;

oneway interface IOspCallback {
    void onOutcome(in String queryId, in int mode, in String answer,
                   in byte[] verifyReport);
}
