// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.change;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.CapabilityControl;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.TrackingFooters;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.SubmitStrategyFactory;
import com.google.gerrit.server.index.ChangeIndex;
import com.google.gerrit.server.index.IndexCollection;
import com.google.gerrit.server.index.Schema;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.ListChildProjects;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.IntPredicate;
import com.google.gerrit.server.query.Predicate;
import com.google.gerrit.server.query.QueryBuilder;
import com.google.gerrit.server.query.QueryParseException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.lib.AbbreviatedObjectId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a query string meant to be applied to change objects.
 */
public class ChangeQueryBuilder extends QueryBuilder<ChangeData> {
  private static final Pattern PAT_LEGACY_ID = Pattern.compile("^[1-9][0-9]*$");
  private static final Pattern PAT_CHANGE_ID =
      Pattern.compile("^[iI][0-9a-f]{4,}.*$");
  private static final Pattern DEF_CHANGE =
      Pattern.compile("^([1-9][0-9]*|[iI][0-9a-f]{4,}.*)$");

  private static final Pattern PAT_COMMIT =
      Pattern.compile("^([0-9a-fA-F]{4," + RevId.LEN + "})$");
  private static final Pattern PAT_EMAIL =
      Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+");

  private static final Pattern PAT_LABEL =
      Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*((=|>=|<=)[+-]?|[+-])\\d+$");

  // NOTE: As new search operations are added, please keep the
  // SearchSuggestOracle up to date.

  public static final String FIELD_AGE = "age";
  public static final String FIELD_BRANCH = "branch";
  public static final String FIELD_CHANGE = "change";
  public static final String FIELD_COMMENT = "comment";
  public static final String FIELD_COMMIT = "commit";
  public static final String FIELD_CONFLICTS = "conflicts";
  public static final String FIELD_DRAFTBY = "draftby";
  public static final String FIELD_FILE = "file";
  public static final String FIELD_IS = "is";
  public static final String FIELD_HAS = "has";
  public static final String FIELD_LABEL = "label";
  public static final String FIELD_LIMIT = "limit";
  public static final String FIELD_MERGEABLE = "mergeable";
  public static final String FIELD_MESSAGE = "message";
  public static final String FIELD_OWNER = "owner";
  public static final String FIELD_OWNERIN = "ownerin";
  public static final String FIELD_PARENTPROJECT = "parentproject";
  public static final String FIELD_PATH = "path";
  public static final String FIELD_PROJECT = "project";
  public static final String FIELD_REF = "ref";
  public static final String FIELD_REVIEWER = "reviewer";
  public static final String FIELD_REVIEWERIN = "reviewerin";
  public static final String FIELD_STARREDBY = "starredby";
  public static final String FIELD_STATUS = "status";
  public static final String FIELD_TOPIC = "topic";
  public static final String FIELD_TR = "tr";
  public static final String FIELD_VISIBLETO = "visibleto";
  public static final String FIELD_WATCHEDBY = "watchedby";

  public static final String ARG_ID_USER = "user";
  public static final String ARG_ID_GROUP = "group";


  private static final QueryBuilder.Definition<ChangeData, ChangeQueryBuilder> mydef =
      new QueryBuilder.Definition<ChangeData, ChangeQueryBuilder>(
          ChangeQueryBuilder.class);

  @SuppressWarnings("unchecked")
  public static boolean hasLimit(Predicate<ChangeData> p) {
    return find(p, IntPredicate.class, FIELD_LIMIT) != null;
  }

  @SuppressWarnings("unchecked")
  public static int getLimit(Predicate<ChangeData> p) {
    return ((IntPredicate<?>) find(p, IntPredicate.class, FIELD_LIMIT)).intValue();
  }

  public static boolean hasNonTrivialSortKeyAfter(Schema<ChangeData> schema,
      Predicate<ChangeData> p) {
    SortKeyPredicate after =
        find(p, SortKeyPredicate.class, "sortkey_after");
    return after != null && after.getMaxValue(schema) > 0;
  }

  public static boolean hasSortKey(Predicate<ChangeData> p) {
    return find(p, SortKeyPredicate.class, "sortkey_after") != null
        || find(p, SortKeyPredicate.class, "sortkey_before") != null;
  }

  @VisibleForTesting
  public static class Arguments {
    final Provider<ReviewDb> db;
    final Provider<ChangeQueryRewriter> rewriter;
    final IdentifiedUser.GenericFactory userFactory;
    final Provider<CurrentUser> self;
    final CapabilityControl.Factory capabilityControlFactory;
    final ChangeControl.GenericFactory changeControlGenericFactory;
    final ChangeData.Factory changeDataFactory;
    final AccountResolver accountResolver;
    final GroupBackend groupBackend;
    final AllProjectsName allProjectsName;
    final PatchListCache patchListCache;
    final GitRepositoryManager repoManager;
    final ProjectCache projectCache;
    final Provider<ListChildProjects> listChildProjects;
    final IndexCollection indexes;
    final SubmitStrategyFactory submitStrategyFactory;
    final ConflictsCache conflictsCache;
    final TrackingFooters trackingFooters;

    @Inject
    @VisibleForTesting
    public Arguments(Provider<ReviewDb> dbProvider,
        Provider<ChangeQueryRewriter> rewriter,
        IdentifiedUser.GenericFactory userFactory,
        Provider<CurrentUser> self,
        CapabilityControl.Factory capabilityControlFactory,
        ChangeControl.GenericFactory changeControlGenericFactory,
        ChangeData.Factory changeDataFactory,
        AccountResolver accountResolver,
        GroupBackend groupBackend,
        AllProjectsName allProjectsName,
        PatchListCache patchListCache,
        GitRepositoryManager repoManager,
        ProjectCache projectCache,
        Provider<ListChildProjects> listChildProjects,
        IndexCollection indexes,
        SubmitStrategyFactory submitStrategyFactory,
        ConflictsCache conflictsCache,
        TrackingFooters trackingFooters) {
      this.db = dbProvider;
      this.rewriter = rewriter;
      this.userFactory = userFactory;
      this.self = self;
      this.capabilityControlFactory = capabilityControlFactory;
      this.changeControlGenericFactory = changeControlGenericFactory;
      this.changeDataFactory = changeDataFactory;
      this.accountResolver = accountResolver;
      this.groupBackend = groupBackend;
      this.allProjectsName = allProjectsName;
      this.patchListCache = patchListCache;
      this.repoManager = repoManager;
      this.projectCache = projectCache;
      this.listChildProjects = listChildProjects;
      this.indexes = indexes;
      this.submitStrategyFactory = submitStrategyFactory;
      this.conflictsCache = conflictsCache;
      this.trackingFooters = trackingFooters;
    }
  }

  public interface Factory {
    ChangeQueryBuilder create(CurrentUser user);
  }

  private final Arguments args;
  private final CurrentUser currentUser;

  @Inject
  public ChangeQueryBuilder(Arguments args, @Assisted CurrentUser currentUser) {
    super(mydef);
    this.args = args;
    this.currentUser = currentUser;
  }

  @VisibleForTesting
  protected ChangeQueryBuilder(
      QueryBuilder.Definition<ChangeData, ? extends ChangeQueryBuilder> def,
      Arguments args, CurrentUser currentUser) {
    super(def);
    this.args = args;
    this.currentUser = currentUser;
  }

  @Operator
  public Predicate<ChangeData> age(String value) {
    return new AgePredicate(value);
  }

  @Operator
  public Predicate<ChangeData> change(String query) {
    if (PAT_LEGACY_ID.matcher(query).matches()) {
      return new LegacyChangeIdPredicate(args, Change.Id.parse(query));

    } else if (PAT_CHANGE_ID.matcher(query).matches()) {
      return new ChangeIdPredicate(args, parseChangeId(query));
    }

    throw new IllegalArgumentException();
  }

  @Operator
  public Predicate<ChangeData> comment(String value) throws QueryParseException {
    ChangeIndex index = args.indexes.getSearchIndex();
    return new CommentPredicate(args, index, value);
  }

  @Operator
  public Predicate<ChangeData> status(String statusName) {
    if ("open".equals(statusName)) {
      return status_open();

    } else if ("closed".equals(statusName)) {
      return ChangeStatusPredicate.closed(args.db);

    } else if ("reviewed".equalsIgnoreCase(statusName)) {
      return new IsReviewedPredicate();

    } else {
      return new ChangeStatusPredicate(statusName);
    }
  }

  public Predicate<ChangeData> status_open() {
    return ChangeStatusPredicate.open(args.db);
  }

  @Operator
  public Predicate<ChangeData> has(String value) {
    if ("star".equalsIgnoreCase(value)) {
      return new IsStarredByPredicate(args, currentUser);
    }

    if ("draft".equalsIgnoreCase(value)) {
      return new HasDraftByPredicate(args, self());
    }

    throw new IllegalArgumentException();
  }

  @Operator
  public Predicate<ChangeData> is(String value) throws QueryParseException {
    if ("starred".equalsIgnoreCase(value)) {
      return new IsStarredByPredicate(args, currentUser);
    }

    if ("watched".equalsIgnoreCase(value)) {
      return new IsWatchedByPredicate(args, currentUser, false);
    }

    if ("visible".equalsIgnoreCase(value)) {
      return is_visible();
    }

    if ("reviewed".equalsIgnoreCase(value)) {
      return new IsReviewedPredicate();
    }

    if ("owner".equalsIgnoreCase(value)) {
      return new OwnerPredicate(self());
    }

    if ("reviewer".equalsIgnoreCase(value)) {
      return new ReviewerPredicate(self());
    }

    if ("mergeable".equalsIgnoreCase(value)) {
      return new IsMergeablePredicate();
    }

    try {
      return status(value);
    } catch (IllegalArgumentException e) {
      // not status: alias?
    }

    throw new IllegalArgumentException();
  }

  @Operator
  public Predicate<ChangeData> commit(String id) {
    return new CommitPredicate(args, AbbreviatedObjectId.fromString(id));
  }

  @Operator
  public Predicate<ChangeData> conflicts(String value) throws OrmException,
      QueryParseException {
    return new ConflictsPredicate(args, value, parseChange(value));
  }

  @Operator
  public Predicate<ChangeData> p(String name) {
    return project(name);
  }

  @Operator
  public Predicate<ChangeData> project(String name) {
    if (name.startsWith("^"))
      return new RegexProjectPredicate(name);
    return new ProjectPredicate(name);
  }

  @Operator
  public Predicate<ChangeData> parentproject(String name) {
    return new ParentProjectPredicate(args.db, args.projectCache,
        args.listChildProjects, args.self, name);
  }

  @Operator
  public Predicate<ChangeData> branch(String name) {
    if (name.startsWith("^"))
      return ref("^" + branchToRef(name.substring(1)));
    return ref(branchToRef(name));
  }

  private static String branchToRef(String name) {
    if (!name.startsWith(Branch.R_HEADS))
      return Branch.R_HEADS + name;
    return name;
  }

  @Operator
  public Predicate<ChangeData> topic(String name) {
    if (name.startsWith("^"))
      return new RegexTopicPredicate(name);
    return new TopicPredicate(name);
  }

  @Operator
  public Predicate<ChangeData> ref(String ref) {
    if (ref.startsWith("^"))
      return new RegexRefPredicate(ref);
    return new RefPredicate(ref);
  }

  @Operator
  public Predicate<ChangeData> f(String file) throws QueryParseException {
    return file(file);
  }

  @Operator
  public Predicate<ChangeData> file(String file) throws QueryParseException {
    if (file.startsWith("^")) {
      return new RegexPathPredicate(FIELD_FILE, file);
    } else {
      return EqualsFilePredicate.create(args, file);
    }
  }

  @Operator
  public Predicate<ChangeData> path(String path) throws QueryParseException {
    if (path.startsWith("^")) {
      return new RegexPathPredicate(FIELD_PATH, path);
    } else {
      return new EqualsPathPredicate(FIELD_PATH, path);
    }
  }

  @Operator
  public Predicate<ChangeData> label(String name) throws QueryParseException,
      OrmException {
    Set<Account.Id> accounts = null;
    AccountGroup.UUID group = null;

    // Parse for:
    // label:CodeReview=1,user=jsmith or
    // label:CodeReview=1,jsmith or
    // label:CodeReview=1,group=android_approvers or
    // label:CodeReview=1,android_approvers
    //  user/groups without a label will first attempt to match user
    String[] splitReviewer = name.split(",", 2);
    name = splitReviewer[0];        // remove all but the vote piece, e.g.'CodeReview=1'

    if (splitReviewer.length == 2) {
      // process the user/group piece
      PredicateArgs lblArgs = new PredicateArgs(splitReviewer[1]);

      for (Map.Entry<String, String> pair : lblArgs.keyValue.entrySet()) {
        if (pair.getKey().equalsIgnoreCase(ARG_ID_USER)) {
          accounts = parseAccount(pair.getValue());
        } else if (pair.getKey().equalsIgnoreCase(ARG_ID_GROUP)) {
          group = parseGroup(pair.getValue()).getUUID();
        } else {
          throw new QueryParseException(
              "Invalid argument identifier '"   + pair.getKey() + "'");
        }
      }

      for (String value : lblArgs.positional) {
       if (accounts != null || group != null) {
          throw new QueryParseException("more than one user/group specified (" +
              value + ")");
        }
        try {
          accounts = parseAccount(value);
        } catch (QueryParseException qpex) {
          // If it doesn't match an account, see if it matches a group
          // (accounts get precedence)
          try {
            group = parseGroup(value).getUUID();
          } catch (QueryParseException e) {
            throw error("Neither user nor group " + value + " found");
          }
        }
      }
    }

    return new LabelPredicate(args.projectCache,
        args.changeControlGenericFactory, args.userFactory, args.db,
        name, accounts, group);
  }

  @Operator
  public Predicate<ChangeData> message(String text) throws QueryParseException {
    ChangeIndex index = args.indexes.getSearchIndex();
    return new MessagePredicate(args, index, text);
  }

  @Operator
  public Predicate<ChangeData> starredby(String who)
      throws QueryParseException, OrmException {
    if ("self".equals(who)) {
      return new IsStarredByPredicate(args, currentUser);
    }
    Set<Account.Id> m = parseAccount(who);
    List<IsStarredByPredicate> p = Lists.newArrayListWithCapacity(m.size());
    for (Account.Id id : m) {
      p.add(new IsStarredByPredicate(args,
          args.userFactory.create(args.db, id)));
    }
    return Predicate.or(p);
  }

  @Operator
  public Predicate<ChangeData> watchedby(String who)
      throws QueryParseException, OrmException {
    Set<Account.Id> m = parseAccount(who);
    List<IsWatchedByPredicate> p = Lists.newArrayListWithCapacity(m.size());
    for (Account.Id id : m) {
      if (currentUser.isIdentifiedUser()
          && id.equals(((IdentifiedUser) currentUser).getAccountId())) {
        p.add(new IsWatchedByPredicate(args, currentUser, false));
      } else {
        p.add(new IsWatchedByPredicate(args,
            args.userFactory.create(args.db, id), true));
      }
    }
    return Predicate.or(p);
  }

  @Operator
  public Predicate<ChangeData> draftby(String who) throws QueryParseException,
      OrmException {
    Set<Account.Id> m = parseAccount(who);
    List<HasDraftByPredicate> p = Lists.newArrayListWithCapacity(m.size());
    for (Account.Id id : m) {
      p.add(new HasDraftByPredicate(args, id));
    }
    return Predicate.or(p);
  }

  @Operator
  public Predicate<ChangeData> visibleto(String who)
      throws QueryParseException, OrmException {
    if ("self".equals(who)) {
      return is_visible();
    }
    Set<Account.Id> m = args.accountResolver.findAll(who);
    if (!m.isEmpty()) {
      List<Predicate<ChangeData>> p = Lists.newArrayListWithCapacity(m.size());
      for (Account.Id id : m) {
        return visibleto(args.userFactory.create(args.db, id));
      }
      return Predicate.or(p);
    }

    // If its not an account, maybe its a group?
    //
    Collection<GroupReference> suggestions = args.groupBackend.suggest(who, null);
    if (!suggestions.isEmpty()) {
      HashSet<AccountGroup.UUID> ids = new HashSet<AccountGroup.UUID>();
      for (GroupReference ref : suggestions) {
        ids.add(ref.getUUID());
      }
      return visibleto(new SingleGroupUser(args.capabilityControlFactory, ids));
    }

    throw error("No user or group matches \"" + who + "\".");
  }

  public Predicate<ChangeData> visibleto(CurrentUser user) {
    return new IsVisibleToPredicate(args.db, //
        args.changeControlGenericFactory, //
        user);
  }

  public Predicate<ChangeData> is_visible() {
    return visibleto(currentUser);
  }

  @Operator
  public Predicate<ChangeData> o(String who)
      throws QueryParseException, OrmException {
    return owner(who);
  }

  @Operator
  public Predicate<ChangeData> owner(String who) throws QueryParseException,
      OrmException {
    Set<Account.Id> m = parseAccount(who);
    List<OwnerPredicate> p = Lists.newArrayListWithCapacity(m.size());
    for (Account.Id id : m) {
      p.add(new OwnerPredicate(id));
    }
    return Predicate.or(p);
  }

  @Operator
  public Predicate<ChangeData> ownerin(String group)
      throws QueryParseException {
    GroupReference g = GroupBackends.findBestSuggestion(args.groupBackend, group);
    if (g == null) {
      throw error("Group " + group + " not found");
    }
    return new OwnerinPredicate(args.db, args.userFactory, g.getUUID());
  }

  @Operator
  public Predicate<ChangeData> r(String who)
      throws QueryParseException, OrmException {
    return reviewer(who);
  }

  @Operator
  public Predicate<ChangeData> reviewer(String who)
      throws QueryParseException, OrmException {
    Set<Account.Id> m = parseAccount(who);
    List<ReviewerPredicate> p = Lists.newArrayListWithCapacity(m.size());
    for (Account.Id id : m) {
      p.add(new ReviewerPredicate(id));
    }
    return Predicate.or(p);
  }

  @Operator
  public Predicate<ChangeData> reviewerin(String group)
      throws QueryParseException {
    GroupReference g = GroupBackends.findBestSuggestion(args.groupBackend, group);
    if (g == null) {
      throw error("Group " + group + " not found");
    }
    return new ReviewerinPredicate(args.db, args.userFactory, g.getUUID());
  }

  @Operator
  public Predicate<ChangeData> tr(String trackingId) {
    return new TrackingIdPredicate(args.trackingFooters, trackingId);
  }

  @Operator
  public Predicate<ChangeData> bug(String trackingId) {
    return tr(trackingId);
  }

  @Operator
  public Predicate<ChangeData> limit(String limit) {
    return limit(Integer.parseInt(limit));
  }

  static class LimitPredicate extends IntPredicate<ChangeData> {
    LimitPredicate(int limit) {
      super(FIELD_LIMIT, limit);
    }

    @Override
    public boolean match(ChangeData object) {
      return true;
    }

    @Override
    public int getCost() {
      return 0;
    }
  }

  public Predicate<ChangeData> limit(int limit) {
    return new LimitPredicate(limit);
  }

  @Operator
  public Predicate<ChangeData> sortkey_after(String sortKey) {
    return new SortKeyPredicate.After(
        BasicChangeRewrites.schema(args.indexes), args.db, sortKey);
  }

  @Operator
  public Predicate<ChangeData> sortkey_before(String sortKey) {
    return new SortKeyPredicate.Before(
        BasicChangeRewrites.schema(args.indexes), args.db, sortKey);
  }

  @Operator
  public Predicate<ChangeData> resume_sortkey(String sortKey) {
    return sortkey_before(sortKey);
  }

  @Override
  protected Predicate<ChangeData> defaultField(String query)
      throws QueryParseException {
    if (query.startsWith("refs/")) {
      return ref(query);

    } else if (DEF_CHANGE.matcher(query).matches()) {
      return change(query);

    } else if (PAT_COMMIT.matcher(query).matches()) {
      return commit(query);

    } else if (PAT_EMAIL.matcher(query).find()) {
      try {
        return Predicate.or(owner(query), reviewer(query));
      } catch (OrmException err) {
        throw error("Cannot lookup user", err);
      }

    } else if (PAT_LABEL.matcher(query).find()) {
      try {
        return label(query);
      } catch (OrmException err) {
        throw error("Cannot lookup user", err);
      }

    } else {
      // Try to match a project name by substring query.
      final List<ProjectPredicate> predicate =
          new ArrayList<ProjectPredicate>();
      for (Project.NameKey name : args.projectCache.all()) {
        if (name.get().toLowerCase().contains(query.toLowerCase())) {
          predicate.add(new ProjectPredicate(name.get()));
        }
      }

      // If two or more projects contains "query" as substring create an
      // OrPredicate holding predicates for all these projects, otherwise if
      // only one contains that, return only that one predicate by itself.
      if (predicate.size() == 1) {
        return predicate.get(0);
      } else if (predicate.size() > 1) {
        return Predicate.or(predicate);
      }

      throw error("Unsupported query:" + query);
    }
  }

  private Set<Account.Id> parseAccount(String who)
      throws QueryParseException, OrmException {
    if ("self".equals(who)) {
      return Collections.singleton(self());
    }
    Set<Account.Id> matches = args.accountResolver.findAll(who);
    if (matches.isEmpty()) {
      throw error("User " + who + " not found");
    }
    return matches;
  }

  private GroupReference parseGroup(String group) throws QueryParseException {
    GroupReference g = GroupBackends.findBestSuggestion(args.groupBackend,
        group);
    if (g == null) {
      throw error("Group " + group + " not found");
    }
    return g;
  }

  private List<Change> parseChange(String value) throws OrmException,
      QueryParseException {
    if (PAT_LEGACY_ID.matcher(value).matches()) {
      return Collections.singletonList(args.db.get().changes()
          .get(Change.Id.parse(value)));
    } else if (PAT_CHANGE_ID.matcher(value).matches()) {
      Change.Key a = new Change.Key(parseChangeId(value));
      List<Change> changes =
          args.db.get().changes().byKeyRange(a, a.max()).toList();
      if (changes.isEmpty()) {
        throw error("Change " + value + " not found");
      }
      return changes;
    }

    throw error("Change " + value + " not found");
  }

  private static String parseChangeId(String value) {
    if (value.charAt(0) == 'i') {
      value = "I" + value.substring(1);
    }
    return value;
  }

  private Account.Id self() {
    if (currentUser.isIdentifiedUser()) {
      return ((IdentifiedUser) currentUser).getAccountId();
    }
    throw new IllegalArgumentException();
  }
}
