package com.trainingapp.server

import com.trainingapp.server.db.sqlState
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MigrationTest {
    private val db = TestDatabase.fresh()

    private fun exec(sql: String) = db.txBlocking { TransactionManager.current().exec(sql) }

    @Test
    fun `V1 and V2 migrate and the app role can use the module schemas`() {
        val count = db.txBlocking {
            var n = 0
            TransactionManager.current().exec(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('identity','catalogue','scheduling','billing','training','messaging','media','platform','coaching')",
            ) { rs -> rs.next(); n = rs.getInt(1) }
            n
        }
        assertTrue(count > 40, "expected the module tables, got $count")
        exec("SELECT * FROM catalogue.public_trainer_availability")
    }

    @Test
    fun `immutable rows cannot be changed by the app role`() {
        val trainer = Fixtures(Services(db, com.trainingapp.server.config.ServerConfig.fromEnv(mapOf("TRAINER_PROFILE" to "local")))).trainer("immutable")
        for (sql in listOf(
            "UPDATE catalogue.policy_version SET grace_minutes = 10 WHERE id = '${trainer.policyId}'",
            "DELETE FROM catalogue.policy_tier WHERE policy_version_id = '${trainer.policyId}'",
        )) {
            try {
                exec(sql)
                fail("forbid_update must reject: $sql")
            } catch (e: Exception) {
                assertEquals("P0001", e.sqlState())
            }
        }
    }

    @Test
    fun `the app role cannot drop the immutability triggers`() {
        try {
            exec("DROP TRIGGER policy_version_immutable ON catalogue.policy_version")
            fail("the DML role must not be able to drop triggers")
        } catch (e: Exception) {
            assertEquals("42501", e.sqlState()) // insufficient_privilege
        }
    }
}
