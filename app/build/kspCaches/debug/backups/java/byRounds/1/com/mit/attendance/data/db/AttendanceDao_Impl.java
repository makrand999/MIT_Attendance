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
import com.mit.attendance.model.AttendanceEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
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
public final class AttendanceDao_Impl implements AttendanceDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<AttendanceEntity> __insertionAdapterOfAttendanceEntity;

  private final Converters __converters = new Converters();

  private final SharedSQLiteStatement __preparedStmtOfMarkAllAsSeen;

  private final SharedSQLiteStatement __preparedStmtOfDeleteForSubject;

  public AttendanceDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfAttendanceEntity = new EntityInsertionAdapter<AttendanceEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `attendance_records` (`subjectId`,`date`,`status`,`startTime`,`endTime`,`isNew`,`seenAt`) VALUES (?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AttendanceEntity entity) {
        statement.bindString(1, entity.getSubjectId());
        statement.bindString(2, entity.getDate());
        statement.bindString(3, entity.getStatus());
        statement.bindString(4, entity.getStartTime());
        statement.bindString(5, entity.getEndTime());
        final int _tmp = __converters.fromBoolean(entity.isNew());
        statement.bindLong(6, _tmp);
        if (entity.getSeenAt() == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, entity.getSeenAt());
        }
      }
    };
    this.__preparedStmtOfMarkAllAsSeen = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "\n"
                + "        UPDATE attendance_records\n"
                + "        SET isNew = 0, seenAt = ?\n"
                + "        WHERE subjectId = ? AND isNew = 1\n"
                + "    ";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteForSubject = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM attendance_records WHERE subjectId = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertIfNotExists(final List<AttendanceEntity> records,
      final Continuation<? super List<Long>> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<List<Long>>() {
      @Override
      @NonNull
      public List<Long> call() throws Exception {
        __db.beginTransaction();
        try {
          final List<Long> _result = __insertionAdapterOfAttendanceEntity.insertAndReturnIdsList(records);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object markAllAsSeen(final String subjectId, final long time,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfMarkAllAsSeen.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, time);
        _argIndex = 2;
        _stmt.bindString(_argIndex, subjectId);
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
          __preparedStmtOfMarkAllAsSeen.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteForSubject(final String subjectId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteForSubject.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, subjectId);
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
          __preparedStmtOfDeleteForSubject.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<AttendanceEntity>> getAttendanceForSubject(final String subjectId) {
    final String _sql = "\n"
            + "        SELECT * FROM attendance_records\n"
            + "        WHERE subjectId = ?\n"
            + "        ORDER BY date DESC, startTime DESC\n"
            + "    ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, subjectId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"attendance_records"}, new Callable<List<AttendanceEntity>>() {
      @Override
      @NonNull
      public List<AttendanceEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfSubjectId = CursorUtil.getColumnIndexOrThrow(_cursor, "subjectId");
          final int _cursorIndexOfDate = CursorUtil.getColumnIndexOrThrow(_cursor, "date");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfStartTime = CursorUtil.getColumnIndexOrThrow(_cursor, "startTime");
          final int _cursorIndexOfEndTime = CursorUtil.getColumnIndexOrThrow(_cursor, "endTime");
          final int _cursorIndexOfIsNew = CursorUtil.getColumnIndexOrThrow(_cursor, "isNew");
          final int _cursorIndexOfSeenAt = CursorUtil.getColumnIndexOrThrow(_cursor, "seenAt");
          final List<AttendanceEntity> _result = new ArrayList<AttendanceEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final AttendanceEntity _item;
            final String _tmpSubjectId;
            _tmpSubjectId = _cursor.getString(_cursorIndexOfSubjectId);
            final String _tmpDate;
            _tmpDate = _cursor.getString(_cursorIndexOfDate);
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final String _tmpStartTime;
            _tmpStartTime = _cursor.getString(_cursorIndexOfStartTime);
            final String _tmpEndTime;
            _tmpEndTime = _cursor.getString(_cursorIndexOfEndTime);
            final boolean _tmpIsNew;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsNew);
            _tmpIsNew = __converters.toBoolean(_tmp);
            final Long _tmpSeenAt;
            if (_cursor.isNull(_cursorIndexOfSeenAt)) {
              _tmpSeenAt = null;
            } else {
              _tmpSeenAt = _cursor.getLong(_cursorIndexOfSeenAt);
            }
            _item = new AttendanceEntity(_tmpSubjectId,_tmpDate,_tmpStatus,_tmpStartTime,_tmpEndTime,_tmpIsNew,_tmpSeenAt);
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
  public Object getAttendanceForSubjectList(final String subjectId,
      final Continuation<? super List<AttendanceEntity>> $completion) {
    final String _sql = "\n"
            + "        SELECT * FROM attendance_records\n"
            + "        WHERE subjectId = ?\n"
            + "        ORDER BY date DESC, startTime DESC\n"
            + "    ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, subjectId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<AttendanceEntity>>() {
      @Override
      @NonNull
      public List<AttendanceEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfSubjectId = CursorUtil.getColumnIndexOrThrow(_cursor, "subjectId");
          final int _cursorIndexOfDate = CursorUtil.getColumnIndexOrThrow(_cursor, "date");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfStartTime = CursorUtil.getColumnIndexOrThrow(_cursor, "startTime");
          final int _cursorIndexOfEndTime = CursorUtil.getColumnIndexOrThrow(_cursor, "endTime");
          final int _cursorIndexOfIsNew = CursorUtil.getColumnIndexOrThrow(_cursor, "isNew");
          final int _cursorIndexOfSeenAt = CursorUtil.getColumnIndexOrThrow(_cursor, "seenAt");
          final List<AttendanceEntity> _result = new ArrayList<AttendanceEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final AttendanceEntity _item;
            final String _tmpSubjectId;
            _tmpSubjectId = _cursor.getString(_cursorIndexOfSubjectId);
            final String _tmpDate;
            _tmpDate = _cursor.getString(_cursorIndexOfDate);
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final String _tmpStartTime;
            _tmpStartTime = _cursor.getString(_cursorIndexOfStartTime);
            final String _tmpEndTime;
            _tmpEndTime = _cursor.getString(_cursorIndexOfEndTime);
            final boolean _tmpIsNew;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsNew);
            _tmpIsNew = __converters.toBoolean(_tmp);
            final Long _tmpSeenAt;
            if (_cursor.isNull(_cursorIndexOfSeenAt)) {
              _tmpSeenAt = null;
            } else {
              _tmpSeenAt = _cursor.getLong(_cursorIndexOfSeenAt);
            }
            _item = new AttendanceEntity(_tmpSubjectId,_tmpDate,_tmpStatus,_tmpStartTime,_tmpEndTime,_tmpIsNew,_tmpSeenAt);
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

  @Override
  public Object countNewEntries(final String subjectId,
      final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM attendance_records WHERE subjectId = ? AND isNew = 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, subjectId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object totalNewEntriesAllSubjects(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM attendance_records WHERE isNew = 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
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
