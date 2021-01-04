package org.gluu.oxauth.service.stat;

import net.agkn.hll.HLL;
import org.apache.commons.lang.StringUtils;
import org.gluu.oxauth.model.common.GrantType;
import org.gluu.oxauth.model.config.Conf;
import org.gluu.oxauth.model.config.ConfigurationFactory;
import org.gluu.oxauth.model.config.StaticConfiguration;
import org.gluu.oxauth.model.stat.Stat;
import org.gluu.oxauth.model.stat.StatEntry;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.persist.exception.EntryPersistenceException;
import org.gluu.persist.model.base.SimpleBranch;
import org.slf4j.Logger;

import javax.ejb.DependsOn;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Yuriy Zabrovarnyy
 */
@ApplicationScoped
@DependsOn("appInitializer")
@Named
public class StatService {

    // January - 202001, December - 202012
    private static final SimpleDateFormat PERIOD_DATE_FORMAT = new SimpleDateFormat("yyyyMM");
    private static final int regwidth = 5;
    private static final int log2m = 15;

    @Inject
    private Logger log;

    @Inject
    private ConfigurationFactory configurationFactory;

    @Inject
    private PersistenceEntryManager entryManager;

    @Inject
    private StaticConfiguration staticConfiguration;

    private String nodeId;
    private String monthlyDn;
    private StatEntry currentEntry;
    private HLL hll;
    private ConcurrentMap<String, Map<String, Long>> tokenCounters;

    public boolean init() {
        try {
            log.info("Initializing Stat Service");
            initNodeId();
            if (StringUtils.isBlank(nodeId)) {
                log.error("Failed to initialize stat service. statNodeId is not set in configuration.");
                return false;
            }
            if (StringUtils.isBlank(getBaseDn())) {
                log.error("Failed to initialize stat service. 'stat' base dn is not set in configuration.");
                return false;
            }

            final Date now = new Date();
            prepareMonthlyBranch(now);
            if (StringUtils.isBlank(monthlyDn)) {
                log.error("Failed to initialize stat service. Failed to prepare monthly branch.");
                return false;
            }
            log.trace("Monthly branch created: " + monthlyDn);

            setupCurrentEntry(now);
            log.info("Initialized Stat Service");
            return true;
        } catch (Exception e) {
            log.error("Failed to initialize Stat Service.", e);
            return false;
        }
    }

    public void updateStat() {
        Date now = new Date();
        prepareMonthlyBranch(now);
        if (StringUtils.isBlank(monthlyDn)) {
            log.error("Failed to update stat. Unable to prepare monthly branch.");
            return;
        }

        setupCurrentEntry(now);

        final Stat stat = currentEntry.getStat();
        stat.setTokenCountPerGrantType(tokenCounters);
        stat.setLastUpdatedAt(now.getTime());

        currentEntry.setUserHllData(new String(hll.toBytes(), StandardCharsets.UTF_8));
        entryManager.merge(currentEntry);
    }

    private void setupCurrentEntry() {
        setupCurrentEntry(new Date());
    }

    private void setupCurrentEntry(Date now) {
        final String month = PERIOD_DATE_FORMAT.format(now);
        String dn = String.format("jansId=%s,%s", nodeId, monthlyDn); // jansId=<id>,ou=yyyyMM,ou=stat,o=gluu

        if (currentEntry != null && month.equals(currentEntry.getStat().getMonth())) {
            return;
        }

        StatEntry entryFromPersistence = entryManager.find(StatEntry.class, dn);
        if (entryFromPersistence != null && month.equals(entryFromPersistence.getStat().getMonth())) {
            hll = HLL.fromBytes(entryFromPersistence.getUserHllData().getBytes(StandardCharsets.UTF_8));
            tokenCounters = new ConcurrentHashMap<>(entryFromPersistence.getStat().getTokenCountPerGrantType());
            currentEntry = entryFromPersistence;
            return;
        }

        if (currentEntry == null) {
            hll = new HLL(log2m, regwidth);
            tokenCounters = new ConcurrentHashMap<>();

            currentEntry = new StatEntry();
            currentEntry.setId(nodeId);
            currentEntry.setDn(dn);
            currentEntry.setUserHllData(new String(hll.toBytes(), StandardCharsets.UTF_8));
            currentEntry.getStat().setMonth(PERIOD_DATE_FORMAT.format(new Date()));
            entryManager.persist(currentEntry);
        }
    }

    private void initNodeId() {
        if (StringUtils.isNotBlank(nodeId)) {
            return;
        }

        String dn = configurationFactory.getBaseConfiguration().getString("oxauth_ConfigurationEntryDN");
        Conf conf = entryManager.find(Conf.class, dn);

        if (StringUtils.isNotBlank(conf.getDynamic().getStatNodeId())) {
            nodeId = conf.getDynamic().getStatNodeId();
            return;
        }

        try {
            nodeId = UUID.randomUUID().toString();
            conf.getDynamic().setStatNodeId(nodeId);
            conf.setRevision(conf.getRevision() + 1);
            entryManager.merge(conf);
            log.info("Updated statNodeId {} successfully", nodeId);
        } catch (Exception e) {
            nodeId = null;
            log.error("Failed to update statNodeId.", e);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getBaseDn() {
        return staticConfiguration.getBaseDn().getStat();
    }

    private void prepareMonthlyBranch(Date now) {
        final String baseDn = getBaseDn();

        final String month = PERIOD_DATE_FORMAT.format(now); // yyyyMM
        monthlyDn = String.format("ou=%s,%s", month, baseDn); // ou=yyyyMM,ou=stat,o=gluu
        if (!entryManager.hasBranchesSupport(baseDn)) {
            return;
        }

        try {
            if (!entryManager.contains(monthlyDn, SimpleBranch.class)) { // Create ou=yyyyMM branch if needed
                createBranch(monthlyDn, month);
            }
        } catch (Exception e) {
            log.error("Failed to prepare monthly branch: " + monthlyDn, e);
            monthlyDn = null;
            throw e;
        }
    }

    public void createBranch(String branchDn, String ou) {
        try {
            SimpleBranch branch = new SimpleBranch();
            branch.setOrganizationalUnitName(ou);
            branch.setDn(branchDn);

            entryManager.persist(branch);
        } catch (EntryPersistenceException ex) {
            // Check if another process added this branch already
            if (!entryManager.contains(branchDn, SimpleBranch.class)) {
                throw ex;
            }
        }
    }

    public void reportActiveUser(String id) {
        if (StringUtils.isBlank(id)) {
            return;
        }
        setupCurrentEntry();
        hll.addRaw(id.hashCode());
    }

    public void reportAccessToken(GrantType grantType) {
        reportToken(grantType, "access_token");
    }

    public void reportIdToken(GrantType grantType) {
        reportToken(grantType, "id_token");
    }

    public void reportRefreshToken(GrantType grantType) {
        reportToken(grantType, "refresh_token");
    }

    public void reportUmaToken(GrantType grantType) {
        reportToken(grantType, "uma_token");
    }


    private void reportToken(GrantType grantType, String tokenKey) {
        if (grantType == null || tokenKey == null) {
            return;
        }

        Map<String, Long> tokenMap = tokenCounters.computeIfAbsent(grantType.getValue(), k -> new ConcurrentHashMap<>());

        Long counter = tokenMap.get(tokenKey);

        if (counter == null) {
            counter = 1L;
        } else {
            counter++;
        }

        tokenMap.put(tokenKey, counter);

    }
}