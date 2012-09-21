package com.todoroo.astrid.sync;

import android.text.TextUtils;

import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.astrid.dao.TagDataDao;
import com.todoroo.astrid.dao.TagOutstandingDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskOutstandingDao;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.TagOutstanding;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.TaskOutstanding;
import com.todoroo.astrid.test.DatabaseTestCase;

public class SyncModelTest extends DatabaseTestCase {

	@Autowired TaskDao taskDao;
	@Autowired TagDataDao tagDataDao;
	
	@Autowired TaskOutstandingDao taskOutstandingDao;
	@Autowired TagOutstandingDao tagOutstandingDao;
	
	public void testCreateTaskMakesUuid() {
		Task task = createTask();
		assertTrue(task.getValue(Task.REMOTE_ID) != 0);
		assertFalse(TextUtils.isEmpty(task.getValue(Task.PROOF_TEXT)));
	}

	public void testCreateTagMakesUuid() {
		TagData tag = createTagData();
		assertTrue(tag.getValue(TagData.REMOTE_ID) != 0);
		assertFalse(TextUtils.isEmpty(tag.getValue(TagData.PROOF_TEXT)));		
	}
	
	public void testCreateTaskMakesOutstandingProofText() {
		Task task = createTask();
		TodorooCursor<TaskOutstanding> cursor = taskOutstandingDao.query(
				Query.select(TaskOutstanding.PROPERTIES)
				.where(Criterion.and(TaskOutstanding.TASK_ID.eq(task.getId()),
						TaskOutstanding.COLUMN_STRING.eq(RemoteModel.PROOF_TEXT_PROPERTY.name))));
		try {
			assertTrue(cursor.getCount() > 0);
		} finally {
			cursor.close();
		}
	}
	
	public void testCreateTagMakesOutstandingProofText() {
		TagData tag = createTagData();
		TodorooCursor<TagOutstanding> cursor = tagOutstandingDao.query(
				Query.select(TagOutstanding.PROPERTIES)
				.where(Criterion.and(TagOutstanding.TAG_DATA_ID.eq(tag.getId()),
						TagOutstanding.COLUMN_STRING.eq(RemoteModel.PROOF_TEXT_PROPERTY.name))));
		try {
			assertTrue(cursor.getCount() > 0);
		} finally {
			cursor.close();
		}		
	}
	
	public void testChangeTaskMakesOutstandingEntries() {
		Task task = createTask();
		String newTitle = "changing task title";
		task.setValue(Task.TITLE, newTitle);
		
		taskDao.save(task);
		TodorooCursor<TaskOutstanding> cursor = taskOutstandingDao.query(Query.select(TaskOutstanding.PROPERTIES)
				.where(Criterion.and(TaskOutstanding.TASK_ID.eq(task.getId()),
						TaskOutstanding.COLUMN_STRING.eq(Task.TITLE.name),
						TaskOutstanding.VALUE_STRING.eq(newTitle))));
		try {
			assertTrue(cursor.getCount() > 0);
		} finally {
			cursor.close();
		}
	}
	
	public void testChangeTagMakesOutstandingEntries() {
		TagData tag = createTagData();
		String newName = "changing tag name";
		tag.setValue(TagData.NAME, newName);
		
		tagDataDao.saveExisting(tag);
		TodorooCursor<TagOutstanding> cursor = tagOutstandingDao.query(Query.select(TagOutstanding.PROPERTIES)
				.where(Criterion.and(TagOutstanding.TAG_DATA_ID.eq(tag.getId()),
						TagOutstanding.COLUMN_STRING.eq(TagData.NAME.name),
						TagOutstanding.VALUE_STRING.eq(newName))));
		try {
			assertTrue(cursor.getCount() > 0);
		} finally {
			cursor.close();
		}
	}
	
	private Task createTask() {
		Task task = new Task();
		task.setValue(Task.TITLE, "new task");
		
		taskDao.createNew(task);
		return task;
	}
	
	private TagData createTagData() {
		TagData tag = new TagData();
		tag.setValue(TagData.NAME, "new tag");
		
		tagDataDao.createNew(tag);
		return tag;
	}
	
}
