package com.blade.mvc.ui;

import com.blade.kit.CollectionKit;

import java.util.HashMap;
import java.util.Map;

/**
 * ModelAndView, Using templates and data
 *
 * @author <a href="mailto:biezhi.me@gmail.com" target="_blank">biezhi</a>
 * @since 1.5
 */
public class ModelAndView {

    /**
     * Data object, the object is placed in the attribute httprequest
     */
    private Map<String, Object> model = CollectionKit.newHashMap(8);

    /**
     * View Page
     */
    private String view;

    public ModelAndView() {
    }

    /**
     * Create an empty view
     *
     * @param view view page
     */
    public ModelAndView(String view) {
        super();
        this.model = new HashMap<>();
        this.view = view;
    }

    /**
     * Create a model view object with data
     *
     * @param model model data
     * @param view  view page
     */
    public ModelAndView(Map<String, Object> model, String view) {
        super();
        this.model = model;
        this.view = view;
    }

    /**
     * Add data to model
     *
     * @param key   key
     * @param value value
     */
    public void add(String key, Object value) {
        this.model.put(key, value);
    }

    /**
     * Remove model data
     *
     * @param key key
     */
    public void remove(String key) {
        this.model.remove(key);
    }

    /**
     * @return Return view page
     */
    public String getView() {
        return view;
    }

    /**
     * Setting view page
     *
     * @param view view page
     */
    public void setView(String view) {
        this.view = view;
    }

    /**
     * @return Return model map
     */
    public Map<String, Object> getModel() {
        return model;
    }

    /**
     * Setting model
     *
     * @param model Storage data map
     */
    public void setModel(Map<String, Object> model) {
        this.model = model;
    }

    @Override
    public String toString() {
        return "view = " + view + ", model = " + model;
    }
}