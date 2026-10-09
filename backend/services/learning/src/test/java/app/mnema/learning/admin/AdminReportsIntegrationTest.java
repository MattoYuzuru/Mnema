package app.mnema.learning.admin;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.usage.UsageClock;
import app.mnema.learning.usage.UsageLedger;
import app.mnema.learning.usage.ReservationScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={"learning.admin.owner-id=10000000-0000-4000-8000-000000000001"})
@Import(AdminReportsIntegrationTest.FixedClock.class)
class AdminReportsIntegrationTest extends PostgresIntegrationTest {
    private static final UUID OWNER=UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @TestConfiguration(proxyBeanMethods=false) static class FixedClock {
        @Bean @Primary UsageClock reportClock() { return () -> Instant.parse("2040-01-09T12:00:00Z"); }
    }
    @Autowired AdminReports reports;
    @Autowired AdminConsoleController controller;
    @Autowired AdminAudit audit;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired UsageLedger ledger;
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void costAndCreditDistributionsUseSeparateSourcesAndEmptySamplesStayNull() {
        var empty=reports.report(new AdminReportRange(java.time.LocalDate.parse("2039-08-01"),java.time.LocalDate.parse("2039-08-02")));
        assertThat(empty.path("ai").path("latency").path("p50Ms").isNull()).isTrue();
        assertThat(empty.path("usage").path("creditsPerUser").path("p50").isNull()).isTrue();
        assertThat(empty.path("ai").path("latency").path("sampleCount").longValue()).isZero();
        assertThat(empty.path("ai").path("latency").path("population").stringValue()).isEqualTo("RECORDED_LATENCY");
        assertThat(empty.path("usage").path("featuresTruncated").booleanValue()).isFalse();
        assertThat(empty.path("financial").path("revenue").path("status").stringValue()).isEqualTo("AVAILABLE");
        assertThat(empty.path("financial").path("revenue").path("paidOrders").longValue()).isZero();
        assertThat(empty.path("financial").path("infrastructure").path("status").stringValue()).isEqualTo("UNAVAILABLE");
        assertThat(empty.path("ai").path("rangeIncludesExpiredData").booleanValue()).isTrue();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        debit(a,-4,"MATERIAL_SHORT",null,null,"2040-01-02T00:00:00Z");
        debit(b,-12,"MATERIAL_LONG",null,null,"2040-01-02T00:00:00Z");
        debit(b,0,null,"STT",60L,"2040-01-02T00:00:00Z");
        call("OK",100,100,"2040-01-02T00:00:00Z");
        call("TRANSIENT",0,300,"2040-01-02T00:00:00Z");
        call("PENDING",0,null,"2040-01-02T00:00:00Z");
        call("OK",999,999,"2040-01-03T00:00:00Z");
        var range=new AdminReportRange(java.time.LocalDate.parse("2040-01-02"),java.time.LocalDate.parse("2040-01-03"));
        var result=reports.report(range);
        assertThat(result.path("ai").path("calls").longValue()).isEqualTo(3);
        assertThat(result.path("ai").path("pendingCalls").longValue()).isEqualTo(1);
        assertThat(result.path("ai").path("failedCalls").longValue()).isEqualTo(1);
        assertThat(result.path("ai").path("estimatedCostMicros").longValue()).isEqualTo(100);
        assertThat(result.path("ai").path("latency").path("p50Ms").doubleValue()).isEqualTo(200);
        assertThat(result.path("ai").path("latency").path("sampleCount").longValue()).as("the pending call has no latency").isEqualTo(2);
        assertThat(result.path("usage").path("activeUsers").longValue()).isEqualTo(2);
        assertThat(result.path("usage").path("creditsDebited").longValue()).isEqualTo(16);
        assertThat(result.path("usage").path("creditsPerUser").path("p50").doubleValue()).isEqualTo(8);
        assertThat(result.path("usage").path("features")).hasSize(3);
        assertThat(result.path("usage").path("topUsers")).hasSize(2);
        assertThat(result.path("usage").path("topUsers").get(0).path("accountId").stringValue()).isEqualTo(b.toString());
        assertThat(result.path("ai").path("byDay")).hasSize(1);
        assertThat(result.path("ai").path("byDay").get(0).path("date").stringValue()).isEqualTo("2040-01-02");
        var user=reports.user(b,range);
        assertThat(user.path("usage").path("creditsDebited").longValue()).isEqualTo(12);
        assertThat(user.path("usage").path("operations")).hasSize(2);
        assertThat(user.path("usage").path("operationsTruncated").booleanValue()).isFalse();
        assertThat(user.path("allowances")).isEmpty();
        var unknown=reports.user(UUID.randomUUID(),range);
        assertThat(unknown.path("learning").path("decks").longValue()).isZero();
    }

    @Test void featureBreakdownsAreFlaggedWhenTruncatedAndRevenueSumsConfirmedBillingOrders() {
        UUID account=UUID.randomUUID();
        for(int n=0;n<66;n++) debit(account,-1,"OPERATION_"+String.format("%02d",n),null,null,"2040-01-05T00:00:00Z");
        var range=new AdminReportRange(java.time.LocalDate.parse("2040-01-05"),java.time.LocalDate.parse("2040-01-06"));
        var wide=reports.report(range).path("usage");
        assertThat(wide.path("features")).hasSize(64);
        assertThat(wide.path("featuresTruncated").booleanValue()).isTrue();
        var user=reports.user(account,range).path("usage");
        assertThat(user.path("operations")).hasSize(64);
        assertThat(user.path("operationsTruncated").booleanValue()).isTrue();
        assertThat(wide.path("creditsDebited").longValue()).as("totals include every operation").isEqualTo(66);
        order("PAID",19900,"2040-01-05T10:00:00Z");order("PAID",29900,"2040-01-05T11:00:00Z");order("REFUNDED",19900,"2040-01-05T12:00:00Z");
        order("PAID",99900,"2040-01-04T23:59:59Z");order("PAID",99900,"2040-01-06T00:00:00Z");order("FAILED",5000,null);
        var revenue=reports.report(range).path("financial").path("revenue");
        assertThat(revenue.path("currency").stringValue()).isEqualTo("RUB");
        assertThat(revenue.path("paidOrders").longValue()).isEqualTo(2);
        assertThat(revenue.path("paidKopecks").longValue()).isEqualTo(49800);
        assertThat(revenue.path("refundedOrders").longValue()).isEqualTo(1);
        assertThat(revenue.path("refundedKopecks").longValue()).isEqualTo(19900);
    }

    @Test void ownerApiRefusesAnotherActorBeforeQueryParsingAndNeverMutatesUsage() throws Exception {
        var stranger=as(UUID.randomUUID());
        assertThat(stranger.perform(get("/admin/console/report")).andReturn().getResponse().getStatus()).isEqualTo(403);
        var owner=as(OWNER);
        assertThat(owner.perform(get("/admin/console/access")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(owner.perform(get("/admin/console/report?from=2040-01-01&to=2040-01-10&extra=x")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(owner.perform(get("/admin/console/report?from=2040-01-01&from=2040-01-02&to=2040-01-10")).andReturn().getResponse().getStatus()).isEqualTo(400);
        long before=jdbc.sql("SELECT count(*) FROM app_learning.usage_allowance").query(Long.class).single();
        var response=owner.perform(get("/admin/console/users/"+UUID.randomUUID()+"?from=2040-01-01&to=2040-01-10")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(JSON.readTree(response.getContentAsString()).path("allowances")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_allowance").query(Long.class).single()).isEqualTo(before);
    }

    @Test void journalIsTransactionalImmutableAndBounded() throws Exception {
        UUID resource=UUID.randomUUID();
        transactions.executeWithoutResult(status -> { audit.append(OWNER,"PROMO_CREATE",resource,UUID.randomUUID()); status.setRollbackOnly(); });
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE resource_id=:id").param("id",resource).query(Long.class).single()).isZero();
        transactions.executeWithoutResult(status -> { for(int n=0;n<55;n++) audit.append(OWNER,"PROMO_ENABLE",resource,null); });
        var first=audit.page(null); assertThat(first.entries()).hasSize(50); assertThat(first.next()).isNotNull();
        assertThat(first.entries().getFirst().outcome()).isEqualTo("SUCCESS");
        var next=audit.page(first.next()); assertThat(next.entries()).isNotEmpty();
        assertThat(first.entries().stream().map(AdminAudit.Entry::auditId).toList()).doesNotContainAnyElementsOf(next.entries().stream().map(AdminAudit.Entry::auditId).toList());
        for(String statement:new String[]{"UPDATE app_learning.admin_audit SET action='PROMO_DISABLE' WHERE resource_id=:id","DELETE FROM app_learning.admin_audit WHERE resource_id=:id"})
            assertThatThrownBy(() -> jdbc.sql(statement).param("id",resource).update()).as(statement).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE app_learning.admin_audit").update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(as(OWNER).perform(get("/admin/console/audit?before=bad")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(as(OWNER).perform(get("/admin/console/audit")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test void inventoryDeduplicatesSourceBytesAndStoredAllowancesRemainUnchanged() {
        UUID owner=UUID.randomUUID(),source=UUID.randomUUID(),variant=UUID.randomUUID(),first=UUID.randomUUID();
        var range=new AdminReportRange(java.time.LocalDate.parse("2040-01-01"),java.time.LocalDate.parse("2040-01-10"));
        var before=reports.report(range).path("media");
        blob(source,1234);blob(variant,200);
        asset(first,owner,source);asset(UUID.randomUUID(),owner,source);
        jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,created_at) VALUES (:id,:asset,0,'poster','poster',:blob,'2040-01-09T12:00:00Z')")
                .param("id",UUID.randomUUID()).param("asset",first).param("blob",variant).update();
        transactions.executeWithoutResult(status -> ledger.reserve(owner,ReservationScope.SESSION,null,null,1));
        var snapshot=jdbc.sql("SELECT row_to_json(a)::text FROM app_learning.usage_allowance a WHERE owner_id=:owner").param("owner",owner).query(String.class).list();
        var balance=jdbc.sql("SELECT row_to_json(b)::text FROM app_learning.usage_balance b WHERE owner_id=:owner").param("owner",owner).query(String.class).list();
        var user=reports.user(owner,range);
        assertThat(user.path("media").path("assets").longValue()).isEqualTo(2);
        assertThat(user.path("media").path("sourceBytes").longValue()).isEqualTo(1234);
        assertThat(user.path("allowances")).hasSize(1);
        var allowance=user.path("allowances").get(0);
        assertThat(allowance.path("periodId").stringValue()).isEqualTo("2040-01");
        assertThat(allowance.path("plan").stringValue()).isEqualTo("FREE");
        assertThat(allowance.path("source").stringValue()).isEqualTo("CONFIG");
        assertThat(allowance.path("total").intValue()).isPositive();
        assertThat(allowance.path("unlocked").intValue()).isPositive();
        assertThat(allowance.path("used").intValue()).isZero();
        assertThat(allowance.path("reserved").intValue()).isEqualTo(1);
        assertThat(allowance.path("updatedAt").stringValue()).isEqualTo("2040-01-09T12:00:00Z");
        assertThat(allowance.path("validUntil").stringValue()).isNotBlank();
        assertThat(user.path("allowanceCoverage").stringValue()).isEqualTo("STORED_SNAPSHOTS");
        assertThat(jdbc.sql("SELECT row_to_json(a)::text FROM app_learning.usage_allowance a WHERE owner_id=:owner").param("owner",owner).query(String.class).list()).isEqualTo(snapshot);
        assertThat(jdbc.sql("SELECT row_to_json(b)::text FROM app_learning.usage_balance b WHERE owner_id=:owner").param("owner",owner).query(String.class).list()).isEqualTo(balance);
        var inventory=reports.report(range).path("media");
        assertThat(inventory.path("blobBytes").longValue()-before.path("blobBytes").longValue()).isEqualTo(1434);
        assertThat(inventory.path("assets").longValue()-before.path("assets").longValue()).isEqualTo(2);
        assertThat(inventory.path("accuracy").stringValue()).isEqualTo("INVENTORY");
        assertThat(reports.user(UUID.randomUUID(),range).path("media").path("sourceBytes").longValue()).isZero();
    }

    private MockMvc as(UUID actor) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg","RS256").subject(actor.toString()).claim("client_id","mnema-admin-web").build()));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    private void debit(UUID account,int credits,String operation,String bucket,Long units,String time) {
        jdbc.sql("INSERT INTO app_learning.usage_ledger_entry(entry_id,owner_id,kind,credits,operation,bucket,units,rate_card_version,period_id,idempotency_key,created_at) VALUES (:id,:owner,'DEBIT',:credits,:operation,:bucket,:units,'rc-v1','2040-01',:key,CAST(:time AS timestamptz))")
                .param("id",UUID.randomUUID()).param("owner",account).param("credits",credits).param("operation",operation).param("bucket",bucket).param("units",units).param("key",UUID.randomUUID().toString()).param("time",time).update();
    }
    private void call(String outcome,long cost,Integer latency,String time) {
        jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,capability,provider,model,request_hash,outcome,cost_micros,latency_ms,created_at) VALUES (:id,'TEXT','stub','test-model',:hash,:outcome,:cost,:latency,CAST(:time AS timestamptz))")
                .param("id",UUID.randomUUID()).param("hash","0".repeat(64)).param("outcome",outcome).param("cost",cost).param("latency",latency).param("time",time).update();
    }
    private void order(String status,long amount,String paidAt) {
        boolean paid=paidAt!=null;
        jdbc.sql("INSERT INTO app_learning.billing_order(order_id,owner_id,plan,period,status,amount_kopecks,list_price_kopecks,paid_at,period_start,period_end,snapshot_id,expires_at,created_at,updated_at) "
                        + "VALUES (:id,:owner,'PLUS','MONTH',:status,:amount,:amount,CAST(:paid AS timestamptz),CAST(:start AS timestamptz),CAST(:end AS timestamptz),:snapshot,'2040-02-01T00:00:00Z','2040-01-01T00:00:00Z','2040-01-01T00:00:00Z')")
                .param("id",UUID.randomUUID()).param("owner",UUID.randomUUID()).param("status",status).param("amount",amount).param("paid",paidAt)
                .param("start",paid ? paidAt : null).param("end",paid ? "2040-03-01T00:00:00Z" : null).param("snapshot",paid ? "billing:"+UUID.randomUUID() : null).update();
    }
    private void blob(UUID id,long bytes) {
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) VALUES (:id,decode(:hash,'hex'),:bytes,'image/png',:key,'2040-01-09T12:00:00Z')")
                .param("id",id).param("hash",id.toString().replace("-","").repeat(2)).param("bytes",bytes).param("key","admin-report/"+id).update();
    }
    private void asset(UUID id,UUID owner,UUID source) {
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,state,source_blob_id,owner_hold_until,created_at,updated_at) VALUES (:id,:owner,:intent,'upload','READY',:source,'2040-02-01T00:00:00Z','2040-01-09T12:00:00Z','2040-01-09T12:00:00Z')")
                .param("id",id).param("owner",owner).param("intent",UUID.randomUUID()).param("source",source).update();
    }
}
