/**
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.actfm.sync;

import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.content.Context;

import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Join;
import com.todoroo.andlib.sql.Query;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskDao.TaskCriteria;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.notes.NoteMetadata;
import com.todoroo.astrid.service.MetadataService;
import com.todoroo.astrid.service.StartupService;
import com.todoroo.astrid.service.TagDataService;
import com.todoroo.astrid.tags.TagService;

public final class ActFmDataService {

    // --- constants

    /** Utility for joining tasks with metadata */
    public static final Join METADATA_JOIN = Join.left(Metadata.TABLE, Task.ID.eq(Metadata.TASK));

    /** NoteMetadata provider string */
    public static final String NOTE_PROVIDER = "actfm-comment"; //$NON-NLS-1$

    // --- instance variables

    protected final Context context;

    @Autowired TaskDao taskDao;

    @Autowired MetadataService metadataService;

    @Autowired ActFmPreferenceService actFmPreferenceService;

    @Autowired TagDataService tagDataService;

    public ActFmDataService() {
        this.context = ContextManager.getContext();
        DependencyInjectionService.getInstance().inject(this);
    }

    // --- task and metadata methods

    /**
     * Clears metadata information. Used when user logs out of service
     */
    public void clearMetadata() {
        ContentValues values = new ContentValues();
        values.put(Task.REMOTE_ID.name, 0);
        taskDao.updateMultiple(values, Criterion.all);
    }

    /**
     * Currently, this method does nothing, there is an alternate method to create tasks
     * @param properties
     * @return
     */
    public TodorooCursor<Task> getLocallyCreated(Property<?>[] properties) {
        return taskDao.query(Query.select(properties).where(Criterion.and(TaskCriteria.isActive(),
                Task.ID.gt(StartupService.INTRO_TASK_SIZE),
                Task.REMOTE_ID.eq(0))));
    }

    /**
     * Gets tasks that were modified since last sync
     * @param properties
     * @return null if never sync'd
     */
    public TodorooCursor<Task> getLocallyUpdated(Property<?>[] properties) {
        long lastSyncDate = actFmPreferenceService.getLastSyncDate();
        if(lastSyncDate == 0)
            return taskDao.query(Query.select(properties).where(Criterion.none));
        return
            taskDao.query(Query.select(properties).
                    where(Criterion.and(Task.REMOTE_ID.gt(0),
                            Task.MODIFICATION_DATE.gt(lastSyncDate),
                            Task.LAST_SYNC.lt(lastSyncDate))).groupBy(Task.ID));
    }

    /**
     * Searches for a local task with same remote id, updates this task's id
     * @param remoteTask
     * @return true if found local match
     */
    public boolean findLocalMatch(ActFmTaskContainer remoteTask) {
        if(remoteTask.task.getId() != Task.NO_ID)
            return true;
        TodorooCursor<Task> cursor = taskDao.query(Query.select(Task.ID).
                where(Task.REMOTE_ID.eq(remoteTask.task.getValue(Task.REMOTE_ID))));
        try {
            if(cursor.getCount() == 0)
                return false;
            cursor.moveToFirst();
            remoteTask.task.setId(cursor.get(Task.ID));
            return true;
        } finally {
            cursor.close();
        }
    }

    /**
     * Saves a task and its metadata
     * @param task
     */
    public void saveTaskAndMetadata(ActFmTaskContainer task) {
        taskDao.save(task.task);

        metadataService.synchronizeMetadata(task.task.getId(), task.metadata,
                Criterion.or(Criterion.and(MetadataCriteria.withKey(NoteMetadata.METADATA_KEY),
                                NoteMetadata.EXT_PROVIDER.eq(NOTE_PROVIDER)),
                        MetadataCriteria.withKey(TagService.KEY)));
    }

    /**
     * Reads a task and its metadata
     * @param task
     * @return
     */
    public ActFmTaskContainer readTaskAndMetadata(TodorooCursor<Task> taskCursor) {
        Task task = new Task(taskCursor);

        // read tags, notes, etc
        ArrayList<Metadata> metadata = new ArrayList<Metadata>();
        TodorooCursor<Metadata> metadataCursor = metadataService.query(Query.select(Metadata.PROPERTIES).
                where(Criterion.and(MetadataCriteria.byTask(task.getId()),
                        Criterion.or(MetadataCriteria.withKey(TagService.KEY),
                                MetadataCriteria.withKey(NoteMetadata.METADATA_KEY)))));
        try {
            for(metadataCursor.moveToFirst(); !metadataCursor.isAfterLast(); metadataCursor.moveToNext()) {
                metadata.add(new Metadata(metadataCursor));
            }
        } finally {
            metadataCursor.close();
        }

        return new ActFmTaskContainer(task, metadata);
    }

    /**
     * Reads task notes out of a task
     */
    public TodorooCursor<Metadata> getTaskNotesCursor(long taskId) {
        TodorooCursor<Metadata> cursor = metadataService.query(Query.select(Metadata.PROPERTIES).
                where(MetadataCriteria.byTaskAndwithKey(taskId, NoteMetadata.METADATA_KEY)));
        return cursor;
    }

    /**
     * Save / Merge JSON tagData
     * @param tagObject
     * @throws JSONException
     */
    public void saveTagData(JSONObject tagObject) throws JSONException {
        TagData tagData = tagDataService.getTag(tagObject.getString("name"), TagData.ID); //$NON-NLS-1$
        if(tagData == null)
            tagData = new TagData();
        ActFmSyncService.JsonHelper.tagFromJson(tagObject, tagData);
        tagDataService.save(tagData);
    }

}
