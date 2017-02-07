package fk.prof.backend.verticles.leader.election;

import fk.prof.backend.util.IPAddressUtil;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;

public class SharedMapBasedLeaderDiscoveryStore implements LeaderDiscoveryStore {
  private static Logger logger = LoggerFactory.getLogger(SharedMapBasedLeaderDiscoveryStore.class);

  private static String DEFAULT_LEADER_STATE_MAP_NAME = "leaderState";
  private static String LEADER_IP_ADDRESS_MAP_KEY = "ipAddress";

  private LocalMap<String, String> leaderLookupMap;

  public SharedMapBasedLeaderDiscoveryStore(Vertx vertx) {
    this(vertx, DEFAULT_LEADER_STATE_MAP_NAME);
  }

  public SharedMapBasedLeaderDiscoveryStore(Vertx vertx, String leaderStateMapName) {
    leaderLookupMap = vertx.sharedData().getLocalMap(leaderStateMapName);
  }

  @Override
  public void setLeaderIPAddress(String ipAddress) {
    if (ipAddress == null) {
      String previousLeaderAddress = leaderLookupMap.remove(LEADER_IP_ADDRESS_MAP_KEY);
      logger.info(String.format("Removed backend node as leader. Node IP = %s",
          previousLeaderAddress == null ? "" : previousLeaderAddress));
    } else {
      leaderLookupMap.put(LEADER_IP_ADDRESS_MAP_KEY, ipAddress);
      logger.info(String.format("Set backend leader. Node IP = %s", ipAddress));
    }
  }

  @Override
  public String getLeaderIPAddress() {
    return leaderLookupMap.get(LEADER_IP_ADDRESS_MAP_KEY);
  }

  @Override
  public boolean isLeader() {
    String leaderAddress = getLeaderIPAddress();
    return leaderAddress != null && leaderAddress.equals(IPAddressUtil.getIPAddressAsString());
  }
}
