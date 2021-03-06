package com.cn.thinkx.wxapi.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cn.thinkx.wxapi.process.HttpMethod;
import com.cn.thinkx.wxapi.process.MpAccount;
import com.cn.thinkx.wxapi.process.MsgType;
import com.cn.thinkx.wxapi.process.MsgXmlUtil;
import com.cn.thinkx.wxapi.process.WxApi;
import com.cn.thinkx.wxapi.process.WxApiClient;
import com.cn.thinkx.wxapi.process.WxMessageBuilder;
import com.cn.thinkx.wxapi.service.BizService;
import com.cn.thinkx.wxapi.vo.Matchrule;
import com.cn.thinkx.wxapi.vo.MsgRequest;
import com.cn.thinkx.wxcms.domain.AccountFans;
import com.cn.thinkx.wxcms.domain.AccountMenu;
import com.cn.thinkx.wxcms.domain.MsgBase;
import com.cn.thinkx.wxcms.domain.MsgNews;
import com.cn.thinkx.wxcms.domain.MsgText;
import com.cn.thinkx.wxcms.mapper.AccountFansDao;
import com.cn.thinkx.wxcms.mapper.AccountMenuDao;
import com.cn.thinkx.wxcms.mapper.AccountMenuGroupDao;
import com.cn.thinkx.wxcms.mapper.MsgBaseDao;
import com.cn.thinkx.wxcms.mapper.MsgNewsDao;
import com.cn.thinkx.wxcms.mapper.MsgTextDao;
import com.cn.thinkx.wxcms.service.AccountFansService;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * 业务消息处理 
 */
@Service("bizService")
public class BizServiceImpl implements BizService {

	@Autowired
	private MsgBaseDao msgBaseDao;
	@Autowired
	private MsgTextDao msgTextDao;
	@Autowired
	private MsgNewsDao msgNewsDao;
	@Autowired
	private AccountMenuDao menuDao;
	@Autowired
	private AccountMenuGroupDao menuGroupDao;
	@Autowired
	private AccountFansDao fansDao;
	@Autowired
	private AccountFansService accountFansService;

	
	// 处理文本消息
	private String processTextMsg(MsgRequest msgRequest, MpAccount mpAccount) {
		String content = msgRequest.getContent();
		if (!StringUtils.isEmpty(content)) {// 文本消息，默认回复订阅消息
			String tmpContent = content.trim();
			MsgText msgText = msgTextDao.getRandomMsg(content);
			if (msgText != null) {// 回复文本
				return MsgXmlUtil.textToXml(WxMessageBuilder.getMsgResponseText(msgRequest, msgText));
			}
			List<MsgNews> msgNews = msgNewsDao.getRandomMsgByContent(tmpContent, mpAccount.getMsgcount());
			if (!CollectionUtils.isEmpty(msgNews)) {// 回复图文
				return MsgXmlUtil.newsToXml(WxMessageBuilder.getMsgResponseNews(msgRequest, msgNews));
			}
		}
		return null;
	}

	// 处理事件消息
	private String processEventMsg(MsgRequest msgRequest, MpAccount mpAccount, boolean merge) {
		String key = msgRequest.getEventKey();
		if (MsgType.SUBSCRIBE.toString().equals(msgRequest.getEvent())) {// 订阅消息
			this.syncAccountFans(msgRequest.getFromUserName(), mpAccount, merge);
			MsgText text = msgBaseDao.getMsgTextBySubscribe();
			if (text != null) {
				return MsgXmlUtil.textToXml(WxMessageBuilder.getMsgResponseText(msgRequest, text));
			}
		} else if (MsgType.UNSUBSCRIBE.toString().equals(msgRequest.getEvent())) {// 取消订阅消息
			MsgText text = msgBaseDao.getMsgTextByInputCode(MsgType.UNSUBSCRIBE.toString());
			if (text != null) {
				return MsgXmlUtil.textToXml(WxMessageBuilder.getMsgResponseText(msgRequest, text));
			}
		} else {// 点击事件消息
			if (MsgType.VIEW.toString().equals(msgRequest.getEvent())) {// 点击菜单跳转链接时的事件推送
				return null;
			}
			if (MsgType.SCANCODE_WAITMSG.toString().equals(msgRequest.getEvent())) {
				return null;
			}
			if (!StringUtils.isEmpty(key)) {
				// 固定消息 _fix_ ：在创建菜单的时候，做了限制，对应的event_key 加了 _fix_
				if (key.startsWith("_fix_")) {
					String baseIds = key.substring("_fix_".length());
					if (!StringUtils.isEmpty(baseIds)) {
						String[] idArr = baseIds.split(",");
						if (idArr.length > 1) {// 多条图文消息
							List<MsgNews> msgNews = msgBaseDao.listMsgNewsByBaseId(idArr);
							if (msgNews != null && msgNews.size() > 0) {
								return MsgXmlUtil.newsToXml(WxMessageBuilder.getMsgResponseNews(msgRequest, msgNews));
							}
						} else {// 图文消息，或者文本消息
							MsgBase msg = msgBaseDao.getById(baseIds);
							if (msg.getMsgtype().equals(MsgType.Text.toString())) {
								MsgText text = msgBaseDao.getMsgTextByBaseId(baseIds);
								if (text != null) {
									return MsgXmlUtil.textToXml(WxMessageBuilder.getMsgResponseText(msgRequest, text));
								}
							} else {
								List<MsgNews> msgNews = msgBaseDao.listMsgNewsByBaseId(idArr);
								if (msgNews != null && msgNews.size() > 0) {
									return MsgXmlUtil.newsToXml(WxMessageBuilder.getMsgResponseNews(msgRequest, msgNews));
								}
							}
						}
					}
				}
			}
		}
		return null;
	}

	// 发布菜单
	public JSONObject publishMenu(String gid, MpAccount mpAccount) {
		List<AccountMenu> menus = menuDao.listWxMenus(gid);

		Matchrule matchrule = new Matchrule();
		String menuJson = prepareMenus(menus, matchrule);
		JSONObject rstObj = WxApiClient.publishMenus(menuJson, mpAccount);// 创建普通菜单

		if (rstObj != null) {// 成功，更新菜单组
			if (rstObj.containsKey("menuid")) {
				menuGroupDao.updateMenuGroupEnable(gid);
			} else if (rstObj.containsKey("errcode") && rstObj.getInt("errcode") == 0) {
				menuGroupDao.updateMenuGroupEnable(gid);
			}
		}
		return rstObj;
	}
	
	// 发布个性化菜单
	public JSONObject publishMenuConditional(String gid, MpAccount mpAccount) {
		List<AccountMenu> menus = menuDao.listWxMenus(gid);

		Matchrule matchrule = new Matchrule();
		matchrule.setGroup_id(gid);
		String menuJson = prepareMenus(menus, matchrule);
		
		// 以下为创建个性化菜单demo，只为男创建菜单；其他个性化需求 设置 Matchrule 属性即可
		JSONObject rstObj= WxApiClient.publishAddconditionalMenus(menuJson,mpAccount);//创建个性化菜单

		if (rstObj != null) {// 成功，更新菜单组
			if (rstObj.containsKey("menuid")) {
				menuGroupDao.updateMenuGroupEnable(gid);
			} else if (rstObj.containsKey("errcode") && rstObj.getInt("errcode") == 0) {
				menuGroupDao.updateMenuGroupEnable(gid);
			}
		}
		return rstObj;
	}

	// 删除菜单
	public JSONObject deleteMenu(MpAccount mpAccount) {
		JSONObject rstObj = WxApiClient.deleteMenu(mpAccount);
		if (rstObj != null && rstObj.getInt("errcode") == 0) {// 成功，更新菜单组
			menuGroupDao.updateMenuGroupDisable();
		}
		return rstObj;
	}
	
	// 获取用户列表
	public boolean syncAccountFansList(MpAccount mpAccount) {
		String nextOpenId = null;
		AccountFans lastFans = fansDao.getLastOpenId();
		if (lastFans != null) {
			nextOpenId = lastFans.getOpenId();
		}
		return doSyncAccountFansList(nextOpenId, mpAccount);
	}

	// 同步粉丝列表(开发者在这里可以使用递归处理)
	private boolean doSyncAccountFansList(String nextOpenId, MpAccount mpAccount) {
		String url = WxApi.getFansListUrl(WxApiClient.getAccessToken(mpAccount), nextOpenId);
		JSONObject jsonObject = WxApi.httpsRequest(url, HttpMethod.POST, null);
		if (jsonObject.containsKey("errcode")) {
			return false;
		}
		List<AccountFans> fansList = new ArrayList<AccountFans>();
		if (jsonObject.containsKey("data")) {
			if (jsonObject.getJSONObject("data").containsKey("openid")) {
				JSONArray openidArr = jsonObject.getJSONObject("data").getJSONArray("openid");
				int length = 5;// 同步5个
				if (openidArr.size() < length) {
					length = openidArr.size();
				}
				for (int i = 0; i < length; i++) {
					Object openId = openidArr.get(i);
					AccountFans fans = WxApiClient.syncAccountFans(openId.toString(), mpAccount);
					fansList.add(fans);
				}
				// 批处理
				fansDao.addList(fansList);
			}
		}
		return true;
	}



	// 根据openid 获取粉丝，如果没有，同步粉丝
	public AccountFans getFansByOpenId(String openId, MpAccount mpAccount) {
		AccountFans fans = fansDao.getByOpenId(openId);
		if (fans == null) {// 如果没有，添加
			fans = WxApiClient.syncAccountFans(openId, mpAccount);
			if (null != fans) {
				fansDao.add(fans);
			}
		}
		return fans;
	}

	/**
	 * 获取微信公众账号的菜单
	 * 
	 * @param menus
	 *            菜单列表
	 * @param matchrule
	 *            个性化菜单配置
	 * @return
	 */
	private String prepareMenus(List<AccountMenu> menus, Matchrule matchrule) {
		if (!CollectionUtils.isEmpty(menus)) {
			List<AccountMenu> parentAM = new ArrayList<AccountMenu>();
			Map<Long, List<JSONObject>> subAm = new HashMap<Long, List<JSONObject>>();
			for (AccountMenu m : menus) {
				if (m.getParentid() == 0L) {// 一级菜单
					parentAM.add(m);
				} else {// 二级菜单
					if (subAm.get(m.getParentid()) == null) {
						subAm.put(m.getParentid(), new ArrayList<JSONObject>());
					}
					List<JSONObject> tmpMenus = subAm.get(m.getParentid());
					tmpMenus.add(getMenuJSONObj(m));
					subAm.put(m.getParentid(), tmpMenus);
				}
			}
			JSONArray arr = new JSONArray();
			for (AccountMenu m : parentAM) {
				if (subAm.get(m.getId()) != null) {// 有子菜单
					arr.add(getParentMenuJSONObj(m, subAm.get(m.getId())));
				} else {// 没有子菜单
					arr.add(getMenuJSONObj(m));
				}
			}
			JSONObject root = new JSONObject();
			root.put("button", arr);
			root.put("matchrule", JSONObject.fromObject(matchrule).toString());
			return JSONObject.fromObject(root).toString();
		}
		return "error";
	}

	/**
	 * 此方法是构建菜单对象的；构建菜单时，对于 key 的值可以任意定义； 当用户点击菜单时，会把key传递回来；对已处理就可以了
	 * 
	 * @param menu
	 * @return
	 */
	private JSONObject getMenuJSONObj(AccountMenu menu) {
		JSONObject obj = new JSONObject();
		obj.put("name", menu.getName());
		obj.put("type", menu.getMtype());
		if ("click".equals(menu.getMtype())) {// 事件菜单
			if ("fix".equals(menu.getEventType())) {// fix 消息
				obj.put("key", "_fix_" + menu.getMsgId());// 以 _fix_ 开头
			} else {
				if (StringUtils.isEmpty(menu.getInputcode())) {// 如果inputcode为空，默认设置为subscribe，以免创建菜单失败
					obj.put("key", "subscribe");
				} else {
					obj.put("key", menu.getInputcode());
				}
			}
		} else if ("view".equals(menu.getMtype())) {// 链接菜单-view
			obj.put("url", menu.getUrl());
		} else if ("scancode_waitmsg".equals(menu.getMtype())) {// 扫码带提示菜单
			obj.put("key", "rselfmenu_0_0");
			obj.put("sub_button", "[ ]");
		} else if ("scancode_push".equals(menu.getMtype())) {// 扫码推事件菜单
			obj.put("key", "rselfmenu_0_1");
			obj.put("sub_button", "[ ]");
		}
		return obj;
	}
	//移动用户分组
	public boolean updateFansGroupId(String openid,String togroupid,MpAccount mpAccount){
		
		JSONObject rstObj=WxApiClient.updateMembersGorups(openid,togroupid, mpAccount);
		if (rstObj != null && rstObj.getInt("errcode") == 0) {
			return true;
		}
		return false;
	}
	private JSONObject getParentMenuJSONObj(AccountMenu menu, List<JSONObject> subMenu) {
		JSONObject obj = new JSONObject();
		obj.put("name", menu.getName());
		obj.put("sub_button", subMenu);
		return obj;
	}

	@Override
	public String processMsg(MsgRequest msgRequest, MpAccount mpAccount) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AccountFans syncAccountFans(String openId, MpAccount mpAccount, boolean merge) {
		// TODO Auto-generated method stub
		return null;
	}

}
