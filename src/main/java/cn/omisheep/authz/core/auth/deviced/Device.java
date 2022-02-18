package cn.omisheep.authz.core.auth.deviced;

import java.util.Date;
import java.util.Map;

/**
 * qq: 1269670415
 *
 * @author zhou xin chen
 */
public interface Device extends Map<Object, Object>, java.io.Serializable {

    String TYPE = "type";

    String ID = "id";

    String LAST_REQUEST_TIME = "lrt";

    String IP = "ip";

    String getType();

    String getId();

    Date getLastRequestTime();

    String getIp();

    Device setType(String type);

    Device setLastRequestTime(Date lastRequestTime);

    Device setId(String id);

    Device setIp(String ip);
}
