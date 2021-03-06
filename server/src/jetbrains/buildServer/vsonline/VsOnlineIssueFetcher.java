/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.vsonline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.issueTracker.AbstractIssueFetcher;
import jetbrains.buildServer.issueTracker.IssueData;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.cache.EhCacheUtil;
import org.apache.commons.httpclient.Credentials;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Oleg Rybak <oleg.rybak@jetbrains.com>
 */
public class VsOnlineIssueFetcher extends AbstractIssueFetcher {

  private interface Containers {
    String CONTAINER_FIELDS = "fields";
    String CONTAINER_LINKS  = "_links";
    String CONTAINER_HTML   = "html";
  }

  private interface Fields {
    String FIELD_SUMMARY = "System.Title";
    String FIELD_STATE   = "System.State";
    String FIELD_TYPE    = "System.WorkItemType";
    String FIELD_HREF    = "href";
  }

  // host + / collection / area
  // http://account.visualstudio.com/collection/project
  private final Pattern p = Pattern.compile("^(http[s]?://.+\\.visualstudio.com)/(.+)/(.+)/$");

  private static final String URL_TEMPLATE_GET_ISSUE = "%s/%s/_apis/wit/workitems/%s?$expand=all&api-version=%s";

  public VsOnlineIssueFetcher(@NotNull final EhCacheUtil cacheUtil) {
    super(cacheUtil);
  }

  /*
   * see doc:
   * http://www.visualstudio.com/en-us/integrate/reference/reference-vso-work-item-overview-vsi
   * http://www.visualstudio.com/en-us/integrate/reference/reference-vso-work-item-work-items-vsi#byids
   */

  private static final String apiVersion = "1.0"; // rest api version

  // host is sanitized in the form "host/collection/project/"
  @NotNull
  public IssueData getIssue(@NotNull final String host, @NotNull final String id, @Nullable final Credentials credentials) throws Exception {
    final Matcher m = p.matcher(host);
    if (!m.matches()) {
      throw new RuntimeException("Wrong host for issue tracker provided: [" + host + "]");
    }
    final String hostOnly = m.group(1);
    final String collection = m.group(2);
    final String cacheKey = getUrl(host, id);
    final String restUrl = String.format(URL_TEMPLATE_GET_ISSUE, hostOnly, collection, id, apiVersion);
    return getFromCacheOrFetch(cacheKey, new FetchFunction() {
      @NotNull
      public IssueData fetch() throws Exception {
        InputStream body = fetchHttpFile(restUrl, credentials);
        return doGetIssue(body);
      }
    });
  }

  private IssueData doGetIssue(@NotNull final InputStream input) throws Exception {
    final Map map = new ObjectMapper().readValue(input, Map.class);
    return parseIssueData(map);
  }

  private IssueData parseIssueData(@NotNull final Map map) {
    final Map fields = getContainer(map, Containers.CONTAINER_FIELDS);
    final Map links = getContainer(map, Containers.CONTAINER_LINKS);
    final Map html = getContainer(links, Containers.CONTAINER_HTML);
    final String href = getField(html, Fields.FIELD_HREF);

    return new IssueData(
            String.valueOf(map.get("id")),
            CollectionsUtil.asMap(
                    IssueData.SUMMARY_FIELD, getField(fields, Fields.FIELD_SUMMARY),
                    IssueData.STATE_FIELD, getField(fields, Fields.FIELD_STATE),
                    IssueData.TYPE_FIELD, getField(fields, Fields.FIELD_TYPE),
                    "href", href
            ),
            false, // todo: state
            "Feature".equals(getField(fields, Fields.FIELD_TYPE)),
            href
    );
  }

  private Map getContainer(final Map map, @NotNull final String name) {
    return (Map) map.get(name);
  }

  private String getField(final Map map, @NotNull final String name) {
    return (String) map.get(name);
  }

  @NotNull
  public String getUrl(@NotNull String host, @NotNull String id) {
    return host + "_workitems/edit/" + id;
  }
}
