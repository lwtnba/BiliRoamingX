package me.iacn.biliroaming.hooker;

import android.util.ArrayMap;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import me.iacn.biliroaming.BiliBiliPackage;
import me.iacn.biliroaming.network.BiliRoamingApi;

import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setIntField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static me.iacn.biliroaming.Constant.TAG;
import static me.iacn.biliroaming.Constant.TYPE_EPISODE_ID;
import static me.iacn.biliroaming.Constant.TYPE_SEASON_ID;

/**
 * Created by iAcn on 2019/3/27
 * Email i@iacn.me
 */
public class BangumiSeasonHook extends BaseHook {

    private Map<String, Object> lastSeasonInfo;

    public BangumiSeasonHook(ClassLoader classLoader) {
        super(classLoader);
        lastSeasonInfo = new ArrayMap<>();
    }

    @Override
    public void startHook() {
        Log.d(TAG, "startHook: BangumiSeason");

        Class<?> paramsMapClass = findClass(
                "com.bilibili.bangumi.api.uniform.BangumiDetailApiService$UniformSeasonParamsMap", mClassLoader);
        XposedBridge.hookAllConstructors(paramsMapClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Map<String, String> paramMap = (Map) param.thisObject;
                int type = (int) param.args[1];

                switch (type) {
                    case TYPE_SEASON_ID:
                        String seasonId = paramMap.get("season_id");
                        lastSeasonInfo.put("season_id", seasonId);
                        Log.d(TAG, "SeasonInformation: seasonId = " + seasonId);
                        break;
                    case TYPE_EPISODE_ID:
                        String episodeId = paramMap.get("ep_id");
                        lastSeasonInfo.put("episode_id", episodeId);
                        Log.d(TAG, "SeasonInformation: episodeId = " + episodeId);
                        break;
                    default:
                        return;
                }
                lastSeasonInfo.put("type", type);

                String accessKey = paramMap.get("access_key");
                lastSeasonInfo.put("access_key", accessKey);
            }
        });

        Class<?> responseClass = findClass(BiliBiliPackage.getInstance().retrofitResponse(), mClassLoader);
        XposedBridge.hookAllConstructors(responseClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object body = param.args[1];
                Class<?> bangumiApiResponse = BiliBiliPackage.getInstance().bangumiApiResponse();

                // Filter non-bangumi and normal bangumi responses
                if (!bangumiApiResponse.isInstance(body) || isNormalSeason(body)) return;

                // Filter other responses
                // If it isn't bangumi, the type variable will not exist in this map
                if (!lastSeasonInfo.containsKey("type")) return;

                Log.d(TAG, "Limited Bangumi: seasonInfo = " + lastSeasonInfo);

                String accessKey = (String) lastSeasonInfo.get("access_key");
                String content = null;
                switch ((int) lastSeasonInfo.get("type")) {
                    case TYPE_SEASON_ID:
                        String seasonId = (String) lastSeasonInfo.get("season_id");
                        content = BiliRoamingApi.getSeason(seasonId, accessKey);
                        break;
                    case TYPE_EPISODE_ID:
                        String episodeId = (String) lastSeasonInfo.get("episode_id");
                        content = BiliRoamingApi.getEpisode(episodeId, accessKey);
                        break;
                }

                JSONObject contentJson = new JSONObject(content);
                int code = contentJson.optInt("code");

                Log.d(TAG, "Got new season information from proxy server: code = " + code);

                if (code == 0) {
                    Class<?> fastJsonClass = BiliBiliPackage.getInstance().fastJson();
                    Class<?> beanClass = BiliBiliPackage.getInstance().bangumiUniformSeason();

                    JSONObject resultJson = contentJson.optJSONObject("result");
                    Object newResult = callStaticMethod(fastJsonClass,
                            BiliBiliPackage.getInstance().fastJsonParse(), resultJson.toString(), beanClass);

                    setIntField(body, "code", 0);
                    setObjectField(body, "result", newResult);
                }
            }
        });

        findAndHookMethod("com.bilibili.bangumi.viewmodel.detail.BangumiDetailViewModel$b", mClassLoader,
                "call", Object.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        lastSeasonInfo.clear();
                    }
                });
    }

    private boolean isNormalSeason(Object bangumiApiResponse) {
        int code = getIntField(bangumiApiResponse, "code");
        Object result = getObjectField(bangumiApiResponse, "result");

        if (code == -404 && result == null) {
            Log.d(TAG, "SeasonResponse: code = " + code + ", result = null");
            return false;
        }

        Class<?> bangumiSeasonClass = BiliBiliPackage.getInstance().bangumiUniformSeason();
        if (bangumiSeasonClass.isInstance(result)) {
            Log.d(TAG, "SeasonResponse: code = " + code + ", result = " + result);

            List episodes = (List) getObjectField(result, "episodes");
            Object rights = getObjectField(result, "rights");
            boolean areaLimit = getBooleanField(rights, "areaLimit");

            return !areaLimit && episodes.size() != 0;
        }

        return true;
    }
}