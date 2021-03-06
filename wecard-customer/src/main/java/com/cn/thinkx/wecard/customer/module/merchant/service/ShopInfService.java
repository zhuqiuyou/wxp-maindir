package com.cn.thinkx.wecard.customer.module.merchant.service;

import java.util.List;

import com.cn.thinkx.common.wecard.domain.base.ResultHtml;
import com.cn.thinkx.common.wecard.domain.shop.ShopInf;

public interface ShopInfService {


	public int addShopInf(ShopInf shop);

	public int updateShopInf(ShopInf shop);
	
	public int updateShopInfUrl(ShopInf entity);

	public ShopInf getShopInfById(String id);

	public List<ShopInf> findShopInfList(ShopInf shopInf);
	

	/***
	 * 门店 管理 新增 编辑
	 * @param mchntInfId
	 * @param flag 0:新增  1:编辑
	 * @param shopInf
	 * @return
	 */
	public ResultHtml updateShopInfByMerchantInf(String mchntInfId,String flag,ShopInf shopInf);
	
	
	public ShopInf getShopInfByCode(String shopCode);

}
