package io.kguard.responder

import io.fabric8.kubernetes.client.KubernetesClient

/** The only cluster writes the responder makes: delete one pod, or restart one Deployment. */
class Fabric8Cluster(private val client: KubernetesClient, private val namespace: String) : Cluster {

    override fun deploymentOfPod(pod: String): String? {
        val found = client.pods().inNamespace(namespace).withName(pod).get() ?: return null
        val replicaSet = found.metadata.ownerReferences.firstOrNull { it.kind == "ReplicaSet" } ?: return null
        val rs = client.apps().replicaSets().inNamespace(namespace).withName(replicaSet.name).get() ?: return null
        return rs.metadata.ownerReferences.firstOrNull { it.kind == "Deployment" }?.name
    }

    override fun deploymentExists(name: String): Boolean =
        client.apps().deployments().inNamespace(namespace).withName(name).get() != null

    override fun deletePod(pod: String) {
        client.pods().inNamespace(namespace).withName(pod).delete()
    }

    override fun rolloutRestart(deployment: String) {
        client.apps().deployments().inNamespace(namespace).withName(deployment).rolling().restart()
    }

    override fun previousRevision(deployment: String): Long? {
        val revisions = client.apps().replicaSets().inNamespace(namespace).list().items
            .filter { rs -> rs.metadata.ownerReferences.any { it.kind == "Deployment" && it.name == deployment } }
            .mapNotNull { it.metadata.annotations?.get("deployment.kubernetes.io/revision")?.toLongOrNull() }
            .sortedDescending()
        return revisions.getOrNull(1)
    }

    override fun rolloutStuck(deployment: String): Boolean {
        val replicaSets = client.apps().replicaSets().inNamespace(namespace).list().items
            .filter { rs -> rs.metadata.ownerReferences.any { it.kind == "Deployment" && it.name == deployment } }
            .sortedByDescending { it.metadata.annotations?.get("deployment.kubernetes.io/revision")?.toLongOrNull() ?: 0 }
        val newest = replicaSets.firstOrNull() ?: return false
        val newestReady = newest.status?.readyReplicas ?: 0
        val wanted = newest.spec?.replicas ?: 0
        val olderReady = replicaSets.drop(1).sumOf { it.status?.readyReplicas ?: 0 }
        return newestReady < wanted && olderReady > 0
    }

    override fun rollback(deployment: String) {
        client.apps().deployments().inNamespace(namespace).withName(deployment).rolling().undo()
    }
}
