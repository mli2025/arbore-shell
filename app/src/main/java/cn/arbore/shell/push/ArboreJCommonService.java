package cn.arbore.shell.push;

import cn.jpush.android.service.JCommonService;

/**
 * Required by JPush 5.x. Without this Service registered in the manifest
 * the SDK refuses to start the registration flow and RegistrationId stays empty.
 * It does not need any custom logic — extending JCommonService is enough.
 */
public class ArboreJCommonService extends JCommonService {
}
