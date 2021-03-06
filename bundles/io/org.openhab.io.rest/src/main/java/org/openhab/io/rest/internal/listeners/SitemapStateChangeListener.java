/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.io.rest.internal.listeners;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.UriBuilder;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.PerRequestBroadcastFilter;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction.ACTION;
import org.openhab.core.items.Item;
import org.openhab.io.rest.RESTApplication;
import org.openhab.io.rest.internal.resources.ResponseTypeHelper;
import org.openhab.io.rest.internal.resources.SitemapResource;
import org.openhab.io.rest.internal.resources.beans.PageBean;
import org.openhab.io.rest.internal.resources.beans.WidgetBean;
import org.openhab.io.rest.internal.resources.beans.WidgetListBean;
import org.openhab.model.sitemap.Frame;
import org.openhab.model.sitemap.LinkableWidget;
import org.openhab.model.sitemap.Sitemap;
import org.openhab.model.sitemap.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the {@link ResourceStateChangeListener} implementation for sitemap REST requests.
 * Note: We only support suspended requests for page requests, not for complete sitemaps.
 * 
 * @author Kai Kreuzer
 * @author Oliver Mazur
 * @author Dan Cunningham
 * @since 0.9.0
 *
 */
public class SitemapStateChangeListener extends ResourceStateChangeListener {

	private static final Logger logger = LoggerFactory.getLogger(SitemapStateChangeListener.class);
	
	@Override
	public void registerItems() {
		super.registerItems();
		//if other filters have let this through then clear out any cached messages for the client.
		//There should at most be only one message (version of the sitemap) in the cache for a client.
		broadcaster.getBroadcasterConfig().addFilter(new PerRequestBroadcastFilter() {
			
			@Override
			public BroadcastAction filter(Object originalMessage, Object message) {
				return new BroadcastAction(ACTION.CONTINUE,  message);
			}

			@Override
			public BroadcastAction filter(AtmosphereResource resource, Object originalMessage, Object message) {
				//this will clear any cached messages before we add the new one
				UUIDBroadcasterCache uuidCache = (UUIDBroadcasterCache)broadcaster.getBroadcasterConfig().getBroadcasterCache();
				List<Object> entries = uuidCache.retrieveFromCache(null,resource);
				if(entries != null)
					logger.trace("UUID {} had {} previous messages", resource.uuid(), entries.size());
				return new BroadcastAction(ACTION.CONTINUE,  message);
			}
		});
	}
	
	@Override
	protected Object getResponseObject(HttpServletRequest request) {
		PageBean pageBean = getPageBean(request);
		if(pageBean!=null) {
			return pageBean;
    	}
		return null;
	}
		
	@Override
	protected Object getSingleResponseObject(Item item, HttpServletRequest request) {
		PageBean pageBean = getPageBean(request);
		WidgetListBean responseBeam ;
		if(pageBean!=null) {
			responseBeam = new WidgetListBean( getItemsOnPage(pageBean.widgets, item));
			return responseBeam;
	    	
    	}
		return null;
	}


	@Override
	protected Set<String> getRelevantItemNames(String pathInfo) {

        // check, if it is a request for a page of a sitemap 
        if (pathInfo.startsWith("/" + SitemapResource.PATH_SITEMAPS)) {
        	String[] pathSegments = pathInfo.substring(1).split("/");

            if(pathSegments.length>=3) {
            	String sitemapName = pathSegments[1];
            	String pageName = pathSegments[2];

            	Sitemap sitemap = (Sitemap) RESTApplication.getModelRepository().getModel(sitemapName + ".sitemap");
            	if(sitemap!=null) {
            		List<Widget> children = null;
            		if(pageName.equals(sitemapName)) {
            			children = sitemap.getChildren();
            		} else {            		
	            		Widget widget = RESTApplication.getItemUIRegistry().getWidget(sitemap, pageName);
	            		if(widget instanceof LinkableWidget) {
	            			LinkableWidget page = (LinkableWidget) widget;
	            			children = RESTApplication.getItemUIRegistry().getChildren(page);
	            		}
            		}
            		if(children!=null) {
	            		return getRelevantItemNamesForWidgets(children);
            		}
				}
            }
        }
        return new HashSet<String>();
	}

	private Set<String> getRelevantItemNamesForWidgets(List<Widget> children) {
		Set<String> itemNames = new HashSet<String>();
		for(Widget child : children) {
			if (child instanceof Frame) {
				Frame frame = (Frame) child;
				String itemName = frame.getItem();
				if(itemName!=null) {
					itemNames.add(itemName);
				}
				itemNames.addAll(getRelevantItemNamesForWidgets(frame.getChildren()));
			} else {
				String itemName = child.getItem();
				if(itemName!=null) {
					itemNames.add(itemName);
				}
			}
		}
		return itemNames;
	}
	
	private PageBean getPageBean(HttpServletRequest request){
		try {
			String query = request.getQueryString();
		String pathInfo = request.getPathInfo();
		
		String responseType = (new ResponseTypeHelper()).getResponseType(request);
		if(responseType!=null) {
			URI basePath = UriBuilder.fromUri(request.getScheme()+"://"+request.getServerName()+":"+request.getServerPort()+(request.getContextPath().equals("null")?"":request.getContextPath()) + RESTApplication.REST_SERVLET_ALIAS +"/").build();
			if (pathInfo.startsWith("/" + SitemapResource.PATH_SITEMAPS)) {
	        	String[] pathSegments = pathInfo.substring(1).split("/");
	            if(pathSegments.length>=3) {
	            	String sitemapName = pathSegments[1];
	            	String pageId = pathSegments[2];
	            	Sitemap sitemap = (Sitemap) RESTApplication.getModelRepository().getModel(sitemapName + ".sitemap");
	            	if(sitemap!=null) {
						return SitemapResource.getPageBean(sitemapName, pageId, basePath);
	            	}
	            }
	        }
		}
		} catch (Exception e) {
			return null;
		}
		return null;
		
	}
	
	private List <WidgetBean> getItemsOnPage(List<WidgetBean> widgets, Item searchItem){
		List <WidgetBean> foundWidgets = new ArrayList <WidgetBean>();
		try{
		for(WidgetBean widget : widgets) {	
			if(widget.item !=null && widget.item.name.equals(searchItem.getName())){
				foundWidgets.add(widget);
			}
			else{
				if (!widget.widgets.isEmpty()){
					List <WidgetBean> tmpWidgets =  getItemsOnPage(widget.widgets, searchItem);
					if(!tmpWidgets.isEmpty()) {
						foundWidgets.addAll(tmpWidgets); }
					
				}
			}
			
			if (widget.linkedPage != null && widget.linkedPage.widgets != null) {
				List <WidgetBean> tmpWidgets =  getItemsOnPage(widget.linkedPage.widgets, searchItem);
				if(!tmpWidgets.isEmpty()) {
					foundWidgets.addAll(tmpWidgets); }
			}			
		}
		}catch (Exception e){
			logger.error(e.getMessage());
		}
		return foundWidgets;
	}

}
