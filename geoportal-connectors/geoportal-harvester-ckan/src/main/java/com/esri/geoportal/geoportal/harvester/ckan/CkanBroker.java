/*
 * Copyright 2016 Esri, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.esri.geoportal.geoportal.harvester.ckan;

import com.esri.geoportal.commons.constants.ItemType;
import com.esri.geoportal.commons.constants.MimeType;
import com.esri.geoportal.commons.http.BotsHttpClient;
import com.esri.geoportal.commons.meta.Attribute;
import com.esri.geoportal.commons.meta.MapAttribute;
import com.esri.geoportal.commons.meta.MetaBuilder;
import com.esri.geoportal.commons.meta.MetaException;
import com.esri.geoportal.commons.meta.StringAttribute;
import static com.esri.geoportal.commons.meta.util.WKAConstants.WKA_DESCRIPTION;
import static com.esri.geoportal.commons.meta.util.WKAConstants.WKA_IDENTIFIER;
import static com.esri.geoportal.commons.meta.util.WKAConstants.WKA_MODIFIED;
import static com.esri.geoportal.commons.meta.util.WKAConstants.WKA_RESOURCE_URL;
import static com.esri.geoportal.commons.meta.util.WKAConstants.WKA_RESOURCE_URL_SCHEME;
import static com.esri.geoportal.commons.meta.util.WKAConstants.WKA_TITLE;
import com.esri.geoportal.commons.robots.Bots;
import com.esri.geoportal.commons.robots.BotsUtils;
import com.esri.geoportal.geoportal.commons.ckan.client.Client;
import com.esri.geoportal.geoportal.commons.ckan.client.Dataset;
import com.esri.geoportal.geoportal.commons.ckan.client.Resource;
import com.esri.geoportal.geoportal.commons.ckan.client.Response;
import com.esri.geoportal.harvester.api.DataReference;
import com.esri.geoportal.harvester.api.base.SimpleDataReference;
import com.esri.geoportal.harvester.api.defs.EntityDefinition;
import com.esri.geoportal.harvester.api.ex.DataInputException;
import com.esri.geoportal.harvester.api.ex.DataProcessorException;
import com.esri.geoportal.harvester.api.specs.InputBroker;
import com.esri.geoportal.harvester.api.specs.InputConnector;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 * CKAN broker.
 */
/*package*/ class CkanBroker implements InputBroker {
  private static final Logger LOG = LoggerFactory.getLogger(CkanBroker.class);
  private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  
  private final CkanConnector connector;
  private final CkanBrokerDefinitionAdaptor definition;
  private final MetaBuilder metaBuilder;
  
  private CloseableHttpClient httpClient;
  private Client client;
 
  
  /**
   * Creates instance of the broker.
   * @param connector connector
   * @param definition definition
   * @param metaBuilder meta builder
   */
  public CkanBroker(CkanConnector connector, CkanBrokerDefinitionAdaptor definition, MetaBuilder metaBuilder) {
    this.connector = connector;
    this.definition = definition;
    this.metaBuilder = metaBuilder;
  }

  @Override
  public void initialize(InitContext context) throws DataProcessorException {
    CloseableHttpClient http = HttpClients.createDefault();
    if (context.getTask().getTaskDefinition().isIgnoreRobotsTxt()) {
      httpClient = http;
    } else {
      Bots bots = BotsUtils.readBots(definition.getBotsConfig(), http, definition.getBotsMode(), definition.getHostUrl());
      httpClient = new BotsHttpClient(http,bots);
    }
    client = new Client(httpClient, definition.getHostUrl(), definition.getApiKey());
  }

  @Override
  public void terminate() {
    if (httpClient!=null) {
      try {
        httpClient.close();
      } catch (IOException ex) {
        LOG.error(String.format("Error terminating broker."), ex);
      }
    }
  }

  @Override
  public URI getBrokerUri() throws URISyntaxException {
    return new URI("CKAN",definition.getHostUrl().toExternalForm(),null);
  }

  @Override
  public Iterator iterator(IteratorContext iteratorContext) throws DataInputException {
    return new CkanIterator(iteratorContext);
  }

  @Override
  public String toString() {
    return String.format("CKAN [%s]", definition.getHostUrl());
  }

  @Override
  public InputConnector getConnector() {
    return connector;
  }

  @Override
  public EntityDefinition getEntityDefinition() {
    return definition.getEntityDefinition();
  }
  
  /**
   * CKAN iterator.
   */
  private class CkanIterator implements InputBroker.Iterator {
    private final IteratorContext iteratorContext;
    private final TransformerFactory tf = TransformerFactory.newInstance();
    
    private java.util.Iterator<Dataset> dataSetsIter;
    private Dataset dataSet;
    private java.util.Iterator<Resource> resourcesIter;
    
    private final int limit = 10;
    private int offset = 0;

    public CkanIterator(IteratorContext iteratorContext) {
      this.iteratorContext = iteratorContext;
    }

    @Override
    public boolean hasNext() throws DataInputException {
      try {
        if (resourcesIter!=null && resourcesIter.hasNext()) {
          return true;
        }
        
        if (dataSetsIter!=null && dataSetsIter.hasNext()) {
          dataSet = dataSetsIter.next();
          if (dataSet!=null && dataSet.resources!=null) {
            resourcesIter = dataSet.resources.iterator();
          }
          return hasNext();
        }

        Response response = client.listPackages(limit, offset);
        offset+=limit;
        
        if (response!=null && response.result!=null && response.result.results!=null) {
          List<Dataset> results = response.result.results;
          if (results!=null && results.size()>0) {
            dataSetsIter = results.iterator();
            return hasNext();
          }
        }
        
        return false;
      } catch (IOException|URISyntaxException ex) {
        throw new DataInputException(CkanBroker.this, String.format("Error reading data from: %s", this), ex);
      }
    }

    @Override
    public DataReference next() throws DataInputException {
      try {
        Resource resource = resourcesIter.next();
        HashMap<String,Attribute> attrs = new HashMap<>();
        String id = firstNonBlank(resource.id,dataSet.id);
        attrs.put(WKA_IDENTIFIER, new StringAttribute(id));
        attrs.put(WKA_TITLE, new StringAttribute(firstNonBlank(resource.name,dataSet.title,dataSet.name)));
        attrs.put(WKA_DESCRIPTION, new StringAttribute(firstNonBlank(resource.description)));
        attrs.put(WKA_MODIFIED, new StringAttribute(dataSet.metadata_modified));
        attrs.put(WKA_RESOURCE_URL, new StringAttribute(resource.url));
        String schemeName = generateSchemeName(resource.url);
        if (schemeName!=null) {
          attrs.put(WKA_RESOURCE_URL_SCHEME, new StringAttribute(schemeName));
        }
        Document doc = metaBuilder.create(new MapAttribute(attrs));
        DOMSource domSource = new DOMSource(doc);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        Transformer transformer = tf.newTransformer();
        transformer.transform(domSource, result);

        return new SimpleDataReference(getBrokerUri(), definition.getEntityDefinition().getLabel(), id, parseIsoDate(resource.created), URI.create(id), writer.toString().getBytes("UTF-8"), MimeType.APPLICATION_XML);
      } catch (MetaException|TransformerException|URISyntaxException|UnsupportedEncodingException|IllegalArgumentException ex) {
        throw new DataInputException(CkanBroker.this, String.format("Error reading data from: %s", this), ex);
      }
    }
    
    private String firstNonBlank(String...strs) {
      return Arrays.asList(strs).stream().filter(s->!StringUtils.isBlank(s)).findFirst().orElse(null);
    }
    
  }
    
  private String generateSchemeName(String url) {
    String serviceType = url!=null? ItemType.matchPattern(url).stream()
            .filter(it->it.getServiceType()!=null)
            .map(ItemType::getServiceType)
            .findFirst().orElse(null): null;
    if (serviceType!=null) {
      return "urn:x-esri:specification:ServiceType:ArcGIS:"+serviceType;
    }
    HashSet<MimeType> mimes = new HashSet<>();
    ItemType.matchPattern(url).stream()
            .filter(it->it.getServiceType()==null)
            .map(ItemType::getMimeTypes)
            .forEach(a->Arrays.asList(a).stream().forEach(mimes::add));
    MimeType mime = mimes.stream().findFirst().orElse(null);
    return mime!=null? mime.getName(): null;
  }

  /**
   * Parses ISO date
   *
   * @param strDate ISO date as string
   * @return date object or <code>null</code> if unable to parse date
   */
  private static Date parseIsoDate(String strDate) {
    try {
      return Date.from(ZonedDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(strDate)).toInstant());
    } catch (Exception ex) {
      return null;
    }
  }
}
