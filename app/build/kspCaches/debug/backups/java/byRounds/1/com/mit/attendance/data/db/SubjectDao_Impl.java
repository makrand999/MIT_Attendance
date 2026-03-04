package com.mit.attendance.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.mit.attendance.model.SubjectEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class SubjectDao_Impl implements SubjectDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<SubjectEntity> __insertionAdapterOfSubjectEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteAll;

  public SubjectDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfSubjectEntity = new EntityInsertionAdapter<SubjectEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `subjects` (`subjectId`,`subjectName`,`totalPresent`,`totalAbsent`,`totalLecture`,`percentage`,`lastUpdated`) VALUES (?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final SubjectEntity entity) {
        statement.bindString(1, entity.getSubjectId());
        statement.bindString(2, entity.getSubjectName());
        statement.bindLong(3, entity.getTotalPresent());
        statement.bindLong(4, entity.getTotalAbsent());
        statement.bindLong(5, entity.getTotalLecture());
        statement.bindDouble(6, entity.getPercentage());
        statement.bindLong(7, entity.getLastUpdated());
      }
    };
    this.__preparedStmtOfDeleteAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM subjects";
        return _query;
      }
    };
  }

  @Override
  public Object upsertSubjects(final List<SubjectEntity> subjects,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfSubjectEntity.insert(subjects);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteAll.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<SubjectWithNewCount>> getAllSubjectsWithNewCounts() {
    final String _sql = "\n"
            + "        SELECT s.*, COUNT(CASE WHEN a.isNew = 1 THEN 1 END) AS newCount\n"
            + "        FROM subjects s\n"
            + "        LEFT JOIN attendance_records a ON s.subjectId = a.subjectId\n"
            + "        GROUP BY s.subjectId\n"
            + "        ORDER BY s.subjectName\n"
            + "    ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"subjects",
        "attendance_records"}, new Callable<List<SubjectWithNewCount>>() {
      @Override
      @NonNull
      public List<SubjectWithNewCount> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfSubjectId = CursorUtil.getColumnIndexOrThrow(_cursor, "subjectId");
          final int _cursorIndexOfSubjectName = CursorUtil.getColumnIndexOrThrow(_cursor, "subjectName");
          final int _cursorIndexOfTotalPresent = CursorUtil.getColumnIndexOrThrow(_cursor, "totalPresent");
          final int _cursorIndexOfTotalAbsent = CursorUtil.getColumnIndexOrThrow(_cursor, "totalAbsent");
          final int _cursorIndexOfTotalLecture = CursorUtil.getColumnIndexOrThrow(_cursor, "totalLecture");
          final int _cursorIndexOfPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "percentage");
          final int _cursorIndexOfLastUpdated = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdated");
          final int _cursorIndexOfNewCount = CursorUtil.getColumnIndexOrThrow(_cursor, "newCount");
          final List<SubjectWithNewCount> _result = new ArrayList<SubjectWithNewCount>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final SubjectWithNewCount _item;
            final int _tmpNewCount;
            _tmpNewCount = _cursor.getInt(_cursorIndexOfNewCount);
            final SubjectEntity _tmpEntity;
            final String _tmpSubjectId;
            _tmpSubjectId = _cursor.getString(_cursorIndexOfSubjectId);
            final String _tmpSubjectName;
            _tmpSubjectName = _cursor.getString(_cursorIndexOfSubjectName);
            final int _tmpTotalPresent;
            _tmpTotalPresent = _cursor.getInt(_cursorIndexOfTotalPresent);
            final int _tmpTotalAbsent;
            _tmpTotalAbsent = _cursor.getInt(_cursorIndexOfTotalAbsent);
            final int _tmpTotalLecture;
            _tmpTotalLecture = _cursor.getInt(_cursorIndexOfTotalLecture);
            final float _tmpPercentage;
            _tmpPercentage = _cursor.getFloat(_cursorIndexOfPercentage);
            final long _tmpLastUpdated;
            _tmpLastUpdated = _cursor.getLong(_cursorIndexOfLastUpdated);
            _tmpEntity = new SubjectEntity(_tmpSubjectId,_tmpSubjectName,_tmpTotalPresent,_tmpTotalAbsent,_tmpTotalLecture,_tmpPercentage,_tmpLastUpdated);
            _item = new SubjectWithNewCount(_tmpEntity,_tmpNewCount);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getAllSubjectsList(final Continuation<? super List<SubjectEntity>> $completion) {
    final String _sql = "SELECT * FROM subjects ORDER BY subjectName";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<SubjectEntity>>() {
      @Override
      @NonNull
      public List<SubjectEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfSubjectId = CursorUtil.getColumnIndexOrThrow(_cursor, "subjectId");
          final int _cursorIndexOfSubjectName = CursorUtil.getColumnIndexOrThrow(_cursor, "subjectName");
          final int _cursorIndexOfTotalPresent = CursorUtil.getColumnIndexOrThrow(_cursor, "totalPresent");
          final int _cursorIndexOfTotalAbsent = CursorUtil.getColumnIndexOrThrow(_cursor, "totalAbsent");
          final int _cursorIndexOfTotalLecture = CursorUtil.getColumnIndexOrThrow(_cursor, "totalLecture");
          final int _cursorIndexOfPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "percentage");
          final int _cursorIndexOfLastUpdated = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdated");
          final List<SubjectEntity> _result = new ArrayList<SubjectEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final SubjectEntity _item;
            final String _tmpSubjectId;
            _tmpSubjectId = _cursor.getString(_cursorIndexOfSubjectId);
            final String _tmpSubjectName;
            _tmpSubjectName = _cursor.getString(_cursorIndexOfSubjectName);
            final int _tmpTotalPresent;
            _tmpTotalPresent = _cursor.getInt(_cursorIndexOfTotalPresent);
            final int _tmpTotalAbsent;
            _tmpTotalAbsent = _cursor.getInt(_cursorIndexOfTotalAbsent);
            final int _tmpTotalLecture;
            _tmpTotalLecture = _cursor.getInt(_cursorIndexOfTotalLecture);
            final float _tmpPercentage;
            _tmpPercentage = _cursor.getFloat(_cursorIndexOfPercentage);
            final long _tmpLastUpdated;
            _tmpLastUpdated = _cursor.getLong(_cursorIndexOfLastUpdated);
            _item = new SubjectEntity(_tmpSubjectId,_tmpSubjectName,_tmpTotalPresent,_tmpTotalAbsent,_tmpTotalLecture,_tmpPercentage,_tmpLastUpdated);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
