package org.unicode.cldr.web.api;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.json.JSONException;
import org.unicode.cldr.util.CLDRConfig;
import org.unicode.cldr.util.CLDRLocale;
import org.unicode.cldr.util.Level;
import org.unicode.cldr.util.Organization;
import org.unicode.cldr.util.VettingViewer;
import org.unicode.cldr.web.*;
import org.unicode.cldr.web.Dashboard.ReviewOutput;
import org.unicode.cldr.web.VettingViewerQueue.LoadingPolicy;

@Path("/summary")
@Tag(name = "voting", description = "APIs for voting")
public class Summary {

    private static ScheduledFuture autoSnapshotFuture = null;

    /**
     * jsonb enables converting an object to a json string,
     * used for creating snapshots
     */
    private static final Jsonb jsonb = JsonbBuilder.create();

    /**
     * For saving and retrieving "snapshots" of Summary responses
     *
     * Note: for debugging/testing without using db, new SurveySnapshotDb() can be
     * changed here to new SurveySnapshotMap()
     */
    private static final SurveySnapshot snap = new SurveySnapshotDb();

    private static final Logger logger = SurveyLog.forClass(Summary.class);

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Get Priority Items Summary",
        description = "Also known as Vetting Summary, this like a Dashboard for multiple locales.")
    @APIResponses(
        value = {
            @APIResponse(
                responseCode = "200",
                description = "Results of Summary operation",
                content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = SummaryResponse.class)))
        })
    public Response doVettingSummary(
        SummaryRequest request,
        @HeaderParam(Auth.SESSION_HEADER) String sessionString) {
        try {
            CookieSession cs = Auth.getSession(sessionString);
            if (cs == null) {
                return Auth.noSessionResponse();
            }
            if (!UserRegistry.userCanUseVettingSummary(cs.user)) {
                return Response.status(Status.FORBIDDEN).build();
            }
            if (SurveySnapshot.SNAP_CREATE.equals(request.snapshotPolicy)
                && !UserRegistry.userCanCreateSummarySnapshot(cs.user)) {
                return Response.status(Status.FORBIDDEN).build();
            }
            return getPriorityItemsSummary(cs, request);
        } catch (JSONException | IOException ioe) {
            return Response.status(500, "An exception occurred").entity(ioe).build();
        }
    }

    /**
     * Get the response for Priority Items Summary (possibly a snapshot)
     *
     * Each request specifies a loading policy: START, NOSTART, or FORCESTOP.
     * Typically, the client makes a request with START, then makes repeated requests with NOSTART
     * while the responses have status PROCESSING (or WAITING), until there is a response with status READY.
     * The response with status READY contains the actual requested Priority Items Summary data.
     *
     * Each request specifies a snapshot policy: SNAP_NONE, SNAP_CREATE, or SNAP_SHOW.
     *
     * @param cs the CookieSession identifying the user
     * @param request the SummaryRequest
     * @return the Response
     *
     * @throws IOException
     * @throws JSONException
     */
    private Response getPriorityItemsSummary(CookieSession cs, SummaryRequest request) throws IOException, JSONException {
        cs.userDidAction();
        if (SurveySnapshot.SNAP_SHOW.equals(request.snapshotPolicy)) {
            return showSnapshot(request.snapshotId);
        }
        Organization usersOrg = cs.user.vrOrg();
        VettingViewerQueue vvq = VettingViewerQueue.getInstance();
        QueueMemberId qmi = new QueueMemberId(cs);
        SummaryResponse sr = getSummaryResponse(vvq, qmi, usersOrg, request.loadingPolicy);
        if (SurveySnapshot.SNAP_CREATE.equals(request.snapshotPolicy)
            && sr.status == VettingViewerQueue.Status.READY) {
            saveSnapshot(sr);
        }
        return Response.ok(sr, MediaType.APPLICATION_JSON).build();
    }

    /**
     * Get the response for Priority Items Summary (new, not an already existing snapshot)
     *
     * @param vvq the VettingViewerQueue
     * @param qmi the QueueMemberId
     * @param usersOrg the user's organization
     * @param loadingPolicy the LoadingPolicy
     * @return the SummaryResponse
     *
     * @throws IOException
     * @throws JSONException
     */
    private SummaryResponse getSummaryResponse(VettingViewerQueue vvq, QueueMemberId qmi,
            Organization usersOrg, LoadingPolicy loadingPolicy) throws IOException, JSONException {
        SummaryResponse sr = new SummaryResponse();
        VettingViewerQueue.Args args = vvq.new Args(qmi, usersOrg, loadingPolicy);
        VettingViewerQueue.Results results = vvq.new Results();
        sr.message = vvq.getPriorityItemsSummaryOutput(args, results);
        sr.percent = vvq.getPercent();
        sr.status = results.status;
        sr.output = results.output.toString();
        return sr;
    }

    /**
     * Return a Response containing the snapshot with the given id
     *
     * @param snapshotId
     * @return the Response
     */
    private Response showSnapshot(String snapshotId) {
        final String json = snap.get(snapshotId);
        if (json == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        return Response.ok(json).build();
    }

    /**
     * Assign a new snapshot id to sr, then save sr as a json snapshot
     *
     * @param sr the SummaryResponse
     */
    private void saveSnapshot(SummaryResponse sr) {
        sr.snapshotId = SurveySnapshot.newId();
        final String json = jsonb.toJson(sr);
        if (json != null && !json.isEmpty()) {
            snap.put(sr.snapshotId, json);
        }
    }

    @GET
    @Path("/snapshots")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "List All Snapshots",
        description = "Get a list of all available snapshots of the Priority Items Summary")
    @APIResponses(
        value = {
            @APIResponse(
                responseCode = "200",
                description = "Snapshot List",
                content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = SnapshotListResponse.class)))
        })
    public Response listSnapshots(
        @HeaderParam(Auth.SESSION_HEADER) String sessionString) {
        CookieSession cs = Auth.getSession(sessionString);
        if (cs == null) {
            return Auth.noSessionResponse();
        }
        if (!UserRegistry.userCanUseVettingSummary(cs.user)) {
            return Response.status(Status.FORBIDDEN).build();
        }
        cs.userDidAction();
        SnapshotListResponse ret = new SnapshotListResponse(snap.list());
        return Response.ok().entity(ret).build();
    }

    @Schema(description = "Response for List Snapshots request")
    public static final class SnapshotListResponse {

        @Schema(description = "Array of available snapshots")
        public String[] array;

        public SnapshotListResponse(String[] array) {
            this.array = array;
        }
    }


    public static void scheduleAutomaticSnapshots() {
        if (!autoSnapshotsAreEnabled()) {
            return;
        }
        // TODO: schedule for a particular time of night or early morning;
        // for now, for testing, just wait two minutes
        // Reference: https://unicode-org.atlassian.net/browse/CLDR-15369
        final long initialDelayMinutes = 2L;
        final long repeatPeriodMinutes = TimeUnit.MINUTES.convert(1L, TimeUnit.DAYS);
        log("Automatic Summary Snapshots are scheduled to start in " + initialDelayMinutes +
             " minutes, then repeat every " + repeatPeriodMinutes + " minutes");
        final ScheduledExecutorService exServ = SurveyThreadManager.getScheduledExecutorService();
        try {
            Summary summary = new Summary();
            Runnable r = summary.new AutoSnapper();
            autoSnapshotFuture = exServ.scheduleAtFixedRate(r, initialDelayMinutes, repeatPeriodMinutes, TimeUnit.MINUTES);
        } catch (Throwable t) {
            SurveyLog.logException(logger, t, "Exception while scheduling automatic snapshots");
            t.printStackTrace();
        }
    }

    private static boolean autoSnapshotsAreEnabled() {
        if (CLDRConfig.getInstance().getProperty("CLDR_AUTO_SNAP", false)) {
            log("Automatic Summary Snapshots (CLDR_AUTO_SNAP) are enabled");
            return true;
        } else {
            log("Automatic Summary Snapshots (CLDR_AUTO_SNAP) are not enabled");
            return false;
        }
    }

    private class AutoSnapper implements Runnable {
        @Override
        public void run() {
            try {
                makeAutoPriorityItemsSnapshot();
            } catch (Throwable t) {
                SurveyLog.logException(logger, t, "Exception in AutoSnapper");
                t.printStackTrace();
            }
        }
    }

    private void makeAutoPriorityItemsSnapshot() throws IOException, JSONException {
        final VettingViewerQueue vvq = VettingViewerQueue.getInstance();
        final QueueMemberId qmi = new QueueMemberId();
        final Organization usersOrg = VettingViewer.getNeutralOrgForSummary();
        LoadingPolicy loadingPolicy = LoadingPolicy.START;
        SummaryResponse sr;
        int count = 0;
        log("Automatic Summary Snapshot, starting");
        boolean finished = false;
        do {
            sr = getSummaryResponse(vvq, qmi, usersOrg, loadingPolicy);
            loadingPolicy = LoadingPolicy.NOSTART;
            ++count;
            log("Automatic Summary Snapshot, got response " + count + "; percent = " + sr.percent);
            if (sr.status == VettingViewerQueue.Status.WAITING
                || sr.status == VettingViewerQueue.Status.PROCESSING) {
                try {
                    Thread.sleep(10000); // ten seconds
                } catch (InterruptedException e) {
                    finished = true;
                }
            } else {
                finished = true;
            }
        } while (!finished && !autoSnapshotFuture.isCancelled());
        if (sr.status == VettingViewerQueue.Status.READY) {
            saveSnapshot(sr);
            log("Automatic Summary Snapshot, saved " + sr.snapshotId);
        }
        log("Automatic Summary Snapshot, finished; status = " + sr.status);
    }

    /**
     * When Survey Tool is shutting down, cancel any scheduled/running automatic snapshot
     */
    public static void shutdown() {
        try {
            if (autoSnapshotFuture != null && !autoSnapshotFuture.isCancelled()) {
                log("Interrupting running auto-snapshot thread");
                autoSnapshotFuture.cancel(true);
            }
        } catch (Throwable t) {
            SurveyLog.logException(logger, t, "Exception interrupting Automatic Summary Snapshot");
            t.printStackTrace();
        }
    }

    @GET
    @Path("/dashboard/{locale}/{level}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Fetch the Dashboard for a locale",
        description = "Given a locale, get the summary information, aka Dashboard")
    @APIResponses(
        value = {
            @APIResponse(
                responseCode = "200",
                description = "Dashboard results",
                content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ReviewOutput.class))) // TODO: SummaryResults.class
        })
    public Response getDashboard(
        @PathParam("locale") @Schema(required = true, description = "Locale ID") String locale,
        @PathParam("level") @Schema(required = true, description = "Coverage Level") String level,
        @HeaderParam(Auth.SESSION_HEADER) String sessionString) {
        CLDRLocale loc = CLDRLocale.getInstance(locale);
        CookieSession cs = Auth.getSession(sessionString);
        if (cs == null) {
            return Auth.noSessionResponse();
        }
        if (!UserRegistry.userCanModifyLocale(cs.user, loc)) {
            return Response.status(403, "Forbidden").build();
        }
        cs.userDidAction();

        // *Beware*  org.unicode.cldr.util.Level (coverage) ≠ VoteResolver.Level (user)
        Level coverageLevel = org.unicode.cldr.util.Level.fromString(level);
        ReviewOutput ret = new Dashboard().get(loc, cs.user, coverageLevel);

        return Response.ok().entity(ret).build();
    }

    private static void log(String message) {
        // TODO: how to enable logger.info? As currently configured, it doesn't print to console
        // Reference: https://unicode-org.atlassian.net/browse/CLDR-15369
        // logger.info("[Summary.logger] " + message);
        System.out.println(message);
    }
}
