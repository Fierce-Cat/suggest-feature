package io.easystartup.suggestfeature.rest.portal.unauth;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.easystartup.suggestfeature.beans.*;
import io.easystartup.suggestfeature.dto.SearchPostDTO;
import io.easystartup.suggestfeature.filters.UserVisibleException;
import io.easystartup.suggestfeature.loggers.Logger;
import io.easystartup.suggestfeature.loggers.LoggerFactory;
import io.easystartup.suggestfeature.services.AuthService;
import io.easystartup.suggestfeature.services.db.MongoTemplateFactory;
import io.easystartup.suggestfeature.utils.JacksonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * @author indianBond
 */
@Path("/portal/unauth/posts")
@Component
public class PublicPortalPostRestApi {

    private static final String EMPTY_JSON_LIST = "[]";
    private static final String EMPTY_JSON = "{}";
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicPortalPostRestApi.class);
    private final MongoTemplateFactory mongoConnection;
    private final AuthService authService;
    // Loading cache of host vs page
    private final Cache<String, String> hostOrgCache = CacheBuilder.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    private final Cache<SearchPostCacheKey, String> searchPostCache = CacheBuilder.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Autowired
    public PublicPortalPostRestApi(MongoTemplateFactory mongoConnection, AuthService authService) {
        this.mongoConnection = mongoConnection;
        this.authService = authService;
    }

    @GET
    @Path("/init-page")
    @Produces("application/json")
    public Response initPage(@Context HttpServletRequest request) {
        // Find Page.java from request host
        String host = request.getHeader("host");
        String resp = null;
        try {
            resp = hostOrgCache.get(host, () -> {
                Organization org = getOrg(host);
                if (org == null) {
                    return JacksonMapper.toJson(Collections.emptyMap());
                }
                sanitizeOrg(org);
                List<Board> boardList = mongoConnection.getDefaultMongoTemplate().find(new Query(Criteria.where(Board.FIELD_ORGANIZATION_ID).in(org.getId())), Board.class);
                boardList.stream().filter((board) -> !board.isPrivateBoard()).forEach(PublicPortalPostRestApi::sanitizeBoard);
                Map<String, Object> rv = new HashMap<>();
                rv.put("org", org);
                rv.put("boards", boardList);
                return JacksonMapper.toJson(rv);
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        return Response.ok().entity(resp).build();
    }

    private void sanitizeOrg(Organization org) {
        org.setCreatedAt(null);
    }

    private static void sanitizeBoard(Board board) {
        board.setOrganizationId(null);
        board.setCreatedByUserId(null);
        board.setCreatedAt(null);
    }

    @GET
    @Path("/get-roadmap-posts")
    @Produces("application/json")
    public Response getPosts(@Context HttpServletRequest request) {
        String host = request.getHeader("host");
        Organization org = getOrg(host);
        if (org == null || (org.getRoadmapSettings() != null && !org.getRoadmapSettings().isEnabled())) {
            return Response.ok().entity(Collections.emptyList()).build();
        }
        List<Board> boardList = mongoConnection.getDefaultMongoTemplate().find(new Query(Criteria.where(Board.FIELD_ORGANIZATION_ID).is(org.getId())), Board.class);
        Map<String, String> boardIdVsSlug = boardList.stream().collect(Collectors.toMap(Board::getId, Board::getSlug));
        Set<String> disabledBoards = new HashSet<>();
        if (org.getRoadmapSettings() != null && CollectionUtils.isNotEmpty(org.getRoadmapSettings().getDisabledBoards())) {
            disabledBoards.addAll(org.getRoadmapSettings().getDisabledBoards());
        }
        Set<String> boardIds = boardList.stream().filter((board) -> !board.isPrivateBoard() && !disabledBoards.contains(board.getId())).map(Board::getId).collect(Collectors.toSet());
        Criteria criteriaDefinition = Criteria.where(Post.FIELD_BOARD_ID).in(boardIds);
        List<Post> posts = mongoConnection.getDefaultMongoTemplate().find(new Query(criteriaDefinition), Post.class);
        posts.sort(Comparator.comparing(Post::getCreatedAt).reversed());

        for (Post post : posts) {
            post.setBoardSlug(boardIdVsSlug.get(post.getBoardId()));
        }

        // Group posts based on status
        Map<String, List<Post>> postsByStatus = posts.stream().collect(Collectors.groupingBy(Post::getStatus));

        return Response.ok().entity(JacksonMapper.toJson(postsByStatus)).build();
    }

    @POST
    @Path("/search-post")
    @Consumes("application/json")
    @Produces("application/json")
    public Response searchPost(@Context HttpServletRequest request, SearchPostDTO req) throws ExecutionException {
        if (StringUtils.isBlank(req.getQuery())) {
            throw new UserVisibleException("Search query is required");
        }
        req.setQuery(req.getQuery().trim().substring(0, Math.min(150, req.getQuery().length())));
        req.setBoardSlug(req.getBoardSlug().trim().substring(0, Math.min(150, req.getBoardSlug().length())));
        if (StringUtils.isBlank(req.getBoardSlug())) {
            throw new UserVisibleException("Slug is required");
        }
        String host = request.getHeader("host");
        String returnValue = searchPostCache.get(new SearchPostCacheKey(req.getQuery(), req.getBoardSlug(), host), () -> {

            Organization org = getOrg(host);
            if (org == null) {
                return EMPTY_JSON_LIST;
            }
            Criteria boardCriteriaDefinition = Criteria.where(Board.FIELD_ORGANIZATION_ID).is(org.getId()).and(Board.FIELD_SLUG).is(req.getBoardSlug());
            Board board = mongoConnection.getDefaultMongoTemplate().findOne(new Query(boardCriteriaDefinition), Board.class);
            if (board == null || board.isPrivateBoard()) {
                return EMPTY_JSON_LIST;
            }

            Criteria criteriaDefinitionForText = Criteria.where(Post.FIELD_ORGANIZATION_ID).is(org.getId());
            Criteria criteriaDefinitionForRegex = Criteria.where(Post.FIELD_ORGANIZATION_ID).is(org.getId());
            if (StringUtils.isNotBlank(req.getBoardSlug())) {
                criteriaDefinitionForText.and(Post.FIELD_BOARD_ID).is(board.getId());
                criteriaDefinitionForRegex.and(Post.FIELD_BOARD_ID).is(board.getId());
            }

            TextCriteria textCriteria = TextCriteria.forDefaultLanguage().matching(req.getQuery());

            Query queryForText = new Query(criteriaDefinitionForText);
            queryForText.addCriteria(textCriteria);
            queryForText.limit(20);

            List<Post> posts = new CopyOnWriteArrayList<>();

            List<Thread> threads = new ArrayList<>();

            Runnable runnable = () -> {
                posts.addAll(mongoConnection.getDefaultMongoTemplate().find(queryForText, Post.class));
            };
            // new virtual thread every task and wait for it to be done
            threads.add(Thread.startVirtualThread(runnable));

            {
                criteriaDefinitionForRegex.and(Post.FIELD_TITLE).regex(req.getQuery(), "i");
                Query queryForRegex = new Query(criteriaDefinitionForRegex);
                queryForRegex.limit(20);
                threads.add(Thread.startVirtualThread(() -> {
                    posts.addAll(mongoConnection.getDefaultMongoTemplate().find(queryForRegex, Post.class));
                }));
            }
            for (Thread thread : threads) {
                try {
                    thread.join(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException ignored) {
                    // ignore
                }
            }
            // remove posts with same  post Id. cant be compared with object.equals as it is a mongo object
            Set<String> postIds = new HashSet<>();
            posts.removeIf(post -> !postIds.add(post.getId()));
            Collections.sort(posts, Comparator.comparing(Post::getCreatedAt).reversed());
            return JacksonMapper.toJson(posts);
        });

        return Response.ok(returnValue).build();
    }

    @GET
    @Path("/fetch-post")
    @Produces("application/json")
    public Response fetchPost(@Context HttpServletRequest request, @QueryParam("postSlug") @NotBlank String postSlug, @QueryParam("boardSlug") @NotBlank String boardSlug) {
        String host = request.getHeader("host");
        Organization org = getOrg(host);
        if (org == null || (org.getRoadmapSettings() != null && !org.getRoadmapSettings().isEnabled())) {
            return Response.ok().entity(Collections.emptyList()).build();
        }
        Criteria boardCriteriaDefinition = Criteria.where(Board.FIELD_ORGANIZATION_ID).is(org.getId()).and(Board.FIELD_SLUG).is(boardSlug);
        Board board = mongoConnection.getDefaultMongoTemplate().findOne(new Query(boardCriteriaDefinition), Board.class);
        if (board.isPrivateBoard()){
            return Response.ok().entity(Collections.emptyList()).build();
        }
        Criteria criteriaDefinition = Criteria.where(Post.FIELD_BOARD_ID).is(board.getId()).and(Post.FIELD_SLUG).is(postSlug).and(Post.FIELD_ORGANIZATION_ID).is(org.getId());
        Post post = mongoConnection.getDefaultMongoTemplate().findOne(new Query(criteriaDefinition), Post.class);
        post.setBoardSlug(boardSlug);
        populatePost(post);

        return Response.ok().entity(JacksonMapper.toJson(post)).build();
    }

    @GET
    @Path("/get-posts-by-board")
    @Produces("application/json")
    public Response getPostsByBoard(@Context HttpServletRequest request, @QueryParam("slug") @NotBlank String slug) {
        String host = request.getHeader("host");
        Organization org = getOrg(host);
        if (org == null) {
            return Response.ok().entity(Collections.emptyList()).build();
        }
        Criteria criteriaDefinition1 = Criteria.where(Board.FIELD_SLUG).is(slug).and(Board.FIELD_ORGANIZATION_ID).is(org.getId());
        Board board = mongoConnection.getDefaultMongoTemplate().findOne(new Query(criteriaDefinition1), Board.class);
        if (board == null || board.isPrivateBoard()) {
            return Response.ok().entity(Collections.emptyList()).build();
        }
        Criteria criteriaDefinition = Criteria.where(Post.FIELD_BOARD_ID).is(board.getId());
        List<Post> posts = mongoConnection.getDefaultMongoTemplate().find(new Query(criteriaDefinition), Post.class);
        posts.sort(Comparator.comparing(Post::getCreatedAt).reversed());
        posts.forEach(post -> post.setBoardSlug(slug));

        return Response.ok().entity(JacksonMapper.toJson(posts)).build();
    }

    private Organization getOrg(String host) {
        Criteria criteria;
        if (!host.endsWith(".suggestfeature.com")) {
            criteria = Criteria.where(Organization.FIELD_CUSTOM_DOMAIN).is(host);
        } else {
            criteria = Criteria.where(Organization.FIELD_SLUG).is(host.split("\\.")[0]);
        }
        return mongoConnection.getDefaultMongoTemplate().findOne(new Query(criteria), Organization.class);
    }

    private void populatePost(Post post) {
        if (post == null) {
            return;
        }
        Criteria criteriaDefinition = Criteria.where(Voter.FIELD_POST_ID).is(post.getId());
        List<Voter> voters = mongoConnection.getDefaultMongoTemplate().find(new Query(criteriaDefinition), Voter.class);
        post.setVoters(voters);
        post.setVotes(voters.size());

//        for (Voter voter : voters) {
//            if (voter.getUserId().equals(UserContext.current().getUserId())) {
//                post.setSelfVoted(true);
//                break;
//            }
//        }

        Criteria criteriaDefinition1 = Criteria.where(Comment.FIELD_POST_ID).is(post.getId());
        List<Comment> comments = mongoConnection.getDefaultMongoTemplate().find(new Query(criteriaDefinition1), Comment.class);
        post.setComments(comments);

        populateUserInCommentAndPopulateNestedCommentsStructure(comments);


        User userByUserId = authService.getUserByUserId(post.getCreatedByUserId());

        User safeUser = new User();
        safeUser.setId(userByUserId.getId());
        safeUser.setName(userByUserId.getName());
        if (StringUtils.isBlank(userByUserId.getName()) && StringUtils.isNotBlank(userByUserId.getEmail())) {
            safeUser.setName(getNameFromEmail(userByUserId.getEmail()));
        }
        safeUser.setProfilePic(userByUserId.getProfilePic());
        post.setUser(safeUser);
    }

    private static String getNameFromEmail(String email) {
        // Extract name from email
        email = email.substring(0, email.indexOf('@'));
        // if it contains dots or any delimiters split it and capitalize first letter of each word and use just first two words
        if (email.contains(".") || email.contains("_") || email.contains("-")) {
            String[] split = email.split("[._-]");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(2, split.length); i++) {
                sb.append(StringUtils.capitalize(split[i]));
                if (i != split.length - 1) {
                    sb.append(" ");
                }
            }
            return sb.toString();
        }
        return StringUtils.capitalize(email);
    }

    private void populateUserInCommentAndPopulateNestedCommentsStructure(List<Comment> comments) {
        // All comments are already fetched. Now populate user in each comment by making single db call
        // And also populate nested comments structure. Based on replyToCommentId and comments list
        Set<String> userIds = comments.stream().map(Comment::getCreatedByUserId).collect(Collectors.toSet());

        Map<String, User> userIdVsUser = authService.getUsersByUserIds(userIds).stream().map(user -> {
            User safeUser = new User();
            safeUser.setId(user.getId());
            safeUser.setName(user.getName());
            if (StringUtils.isBlank(user.getName()) && StringUtils.isNotBlank(user.getEmail())) {
                safeUser.setName(getNameFromEmail(user.getEmail()));
            }
            safeUser.setProfilePic(user.getProfilePic());
            return safeUser;
        }).collect(Collectors.toMap(User::getId, Function.identity()));

        for (Comment comment : comments) {
            comment.setUser(userIdVsUser.get(comment.getCreatedByUserId()));
        }

        // Desc sort by created at
        Collections.sort(comments, Comparator.comparing(Comment::getCreatedAt).reversed());

        Map<String, Comment> commentIdVsComment = comments.stream().collect(Collectors.toMap(Comment::getId, Function.identity()));
        for (Comment comment : comments) {
            if (StringUtils.isNotBlank(comment.getReplyToCommentId())) {
                Comment parentComment = commentIdVsComment.get(comment.getReplyToCommentId());
                if (parentComment != null) {
                    if (parentComment.getComments() == null) {
                        parentComment.setComments(new ArrayList<>());
                    }
                    parentComment.getComments().add(comment);
                }
            }
        }
    }

    public static class SearchPostCacheKey {
        private final String query;
        private final String boardSlug;
        private final String host;

        public SearchPostCacheKey(String query, String boardSlug, String host) {
            this.query = query;
            this.boardSlug = boardSlug;
            this.host = host;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SearchPostCacheKey that = (SearchPostCacheKey) o;
            return Objects.equals(query, that.query) && Objects.equals(boardSlug, that.boardSlug) && Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(query, boardSlug, host);
        }
    }
}