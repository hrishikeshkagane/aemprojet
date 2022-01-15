package com.test.core.models;


import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.Optional;
import org.apache.sling.models.annotations.injectorspecific.RequestAttribute;

import javax.inject.Inject;
import javax.jcr.query.qom.UpperCase;


@Model(adaptables = Resource.class)
public class PosterModel {
    @Inject @Optional
    private String fileReference;

    @Inject @Optional
    private String alt;

    @Inject @Optional
    private String height;

    @Inject @Optional
    private String occupation;

    @Inject @Optional
    private String description;

    @Inject @Optional
    public Resource occupations;


    public String getOccupation() {
        return occupation;
    }

    public String getDescription() {
        return description;
    }


    public String getHeight() {
        return height;
    }

    public String getWidth() {
        return width;
    }

    private String width;
    public String getFileReference() {
        return fileReference;
    }

    public String getAlt() {
        return alt;
    }
}
