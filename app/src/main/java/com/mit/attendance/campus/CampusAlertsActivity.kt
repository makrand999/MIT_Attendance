package com.mit.attendance.campus

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.*

internal data class AlertsScreen(val session: CampusSession? = null, val classes: List<JsonObject> = emptyList(),
    val subjects: List<JsonObject> = emptyList(), val alerts: List<CachedCampusAlert> = emptyList(),
    val personal: List<CampusPersonalState> = emptyList(), val outgoing: List<CampusOutgoing> = emptyList(),
    val error: String = "", val busy: Boolean = false, val draft: JsonObject? = null, val editing: CachedCampusAlert? = null, val draftAccount:String?=null, val draftClass:String?=null,val saving:Boolean=false)
internal class CampusAlertsViewModel(app: Application) : AndroidViewModel(app) {
    private val auth = CampusRepository(app)
    val repository = CampusAlertsRepository(app)
    private val mutable = MutableStateFlow(AlertsScreen())
    val state = mutable.asStateFlow()
    private var observe: Job? = null
    init { refresh() }
    fun clearForAccountChange() {observe?.cancel();mutable.value=AlertsScreen(error="Campus account changed. Refresh to reconnect.")}
    fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Campus request failed.") } }
    } }
    fun refresh() = action {
        mutable.update { it.copy(busy = true,error = "") }
        try {
            val cached = auth.cached()
            if (cached != null) attach(cached, repository.directory(cached,"classes"))
            val session = auth.refresh()
            val classes = repository.refreshDirectory(session)
            val selected = session.selectedClass?.takeIf { id -> classes.any { it.string("id") == id } } ?: classes.firstOrNull()?.string("id")
            if (selected != null) { repository.select(session,selected); val current=repository.store.read()!!; attach(current,classes); refreshSelected(current) }
            else {
                repository.store.selectClass(session.generation,null)
                repository.db.withTransaction {
                    repository.assertCurrent(session)
                    repository.dao.clearActiveGeneration(session.generation,session.selectedClass)
                    repository.assertCurrent(session)
                }
                attach(repository.store.read()!!,classes)
            }
        } finally { mutable.update { it.copy(busy = false) } }
    }
    private suspend fun attach(session: CampusSession, classes: List<JsonObject>) {
        observe?.cancel()
        if(mutable.value.session?.profile?.id?.let {it!=session.profile.id}==true)mutable.update {it.copy(draft=null,editing=null,draftAccount=null,draftClass=null)}
        val subjects = session.selectedClass?.let { repository.directory(session,"subjects",it) } ?: emptyList()
        mutable.update { it.copy(session = session,classes = classes,subjects = subjects,alerts = emptyList(),personal=emptyList(),outgoing=emptyList()) }
        val c=session.selectedClass ?: return
        observe=viewModelScope.launch {
            combine(repository.dao.alerts(session.profile.id,c),repository.dao.states(session.profile.id,c),repository.dao.observeOutgoing(session.profile.id,c)) { alerts,personal,outgoing -> Triple(alerts,personal,outgoing) }.collect { (alerts,personal,outgoing) ->
                if(runCatching {repository.assertCurrent(session,c)}.isFailure)return@collect
                mutable.update { it.copy(alerts=alerts,personal=personal,outgoing=outgoing) }
            }
        }
    }
    private suspend fun refreshSelected(session: CampusSession) {
        val c=session.selectedClass ?: return
        mutable.update { it.copy(subjects=repository.subjects(session,c)) }
        repository.sync(session)
        CampusPush.bind(getApplication(),session)
        CampusSyncWorker.enqueue(getApplication(),session)
    }
    fun target(classId:String,alertId:String?)=action {
        val session=mutable.value.session ?: error("Connect to Campus first.")
        require(mutable.value.classes.any {it.string("id")==classId}) {"You do not have access to this class."}
        repository.select(session,classId)
        val current=repository.store.read()!!
        if(alertId!=null) {
            require(alertId.matches(Regex("[A-Za-z0-9-]{1,100}")))
            val alert=CampusTransport.authenticated(getApplication(),current).request("v1/classes/$classId/alerts/$alertId").asJsonObject
            check(alert.string("status")!="DELETED") {"This alert was deleted."}
        }
        attach(current,mutable.value.classes);refreshSelected(current)
    }
    fun select(id:String)=action {
        check(mutable.value.draft==null) {"Save or cancel your draft before switching classes."}
        val session=mutable.value.session ?: return@action
        repository.select(session,id)
        val current=repository.store.read()!!
        attach(current,mutable.value.classes)
        refreshSelected(current)
    }
    fun draft(edit:CachedCampusAlert?=null) {
        mutable.update { it.copy(editing=edit,draftAccount=it.session?.profile?.id,draftClass=it.session?.selectedClass,draft=edit?.let { a -> JsonParser.parseString(a.json).asJsonObject.let { raw ->
            jsonObject("subject_id" to a.subjectId,"type" to a.type,"topic" to a.topic,"description" to a.description,"due_at" to a.dueAt,"expires_at" to a.expiresAt,"publish_at" to raw.string("publish_at"),"draft" to (a.status=="DRAFT"))
        } } ?: jsonObject("subject_id" to null,"type" to "NOTICE","topic" to "","description" to "","due_at" to null,"expires_at" to null,"publish_at" to null,"draft" to false)) }
    }
    fun updateDraft(value:JsonObject) { mutable.update { it.copy(draft=value) } }
    fun closeDraft() { mutable.update { it.copy(draft=null,editing=null) } }
    fun save()=action {
        if(mutable.value.saving)return@action
        mutable.update {it.copy(saving=true)}
        try {
        val s=mutable.value;val session=s.session ?: error("Open Campus to connect first.");val c=session.selectedClass ?: error("Select a class.")
        check(s.draftAccount==session.profile.id&&s.draftClass==c) {"This draft belongs to a different account or class."}
        val body=jsonObject("content" to s.draft)
        val editing=s.editing
        if(editing!=null) body.addProperty("expected_version",editing.version)
        repository.queue(session,if(editing==null) "POST" else "PATCH","v1/classes/$c/alerts"+(editing?.let { "/${it.id}" }?:""),editing?.id ?: UUID.randomUUID().toString(),body)
        closeDraft()
        } finally {mutable.update {it.copy(saving=false)}}
    }
    fun personal(alert:CachedCampusAlert,seen:Boolean?=null,hidden:Boolean?=null,trashed:Boolean?=null)=action {
        val session=mutable.value.session ?: return@action
        val old=repository.dao.state(alert.account,alert.classId,alert.id) ?: CampusPersonalState(alert.account,alert.classId,alert.id)
        repository.personal(session,alert.id,old.copy(seen=seen?:old.seen,hidden=hidden?:old.hidden,trashed=trashed?:old.trashed))
    }
    fun delete(alert:CachedCampusAlert)=action { mutable.value.session?.let { repository.queue(it,"DELETE","v1/classes/${alert.classId}/alerts/${alert.id}",alert.id,jsonObject("expected_version" to alert.version)) } }
    fun pending(item:CampusOutgoing,cancel:Boolean)=action { mutable.value.session?.let { repository.retry(it,item,cancel) } }
}

class CampusAlertsActivity : AppCompatActivity() {
    private val model:CampusAlertsViewModel by viewModels()
    private lateinit var content:LinearLayout
    private lateinit var list:LinearLayout
    private lateinit var status:TextView
    private lateinit var classes:Spinner
    private var classIds=emptyList<String>()
    private var targetHandled=false
    private var focusedAlert:String?=null
    private var hidden=false
    private var trash=false
    private var archive=false
    private var editor:AlertDialog?=null
    private var renderJob:Job?=null
    private var socket:Job?=null
    private var socketGeneration:String?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        focusedAlert=if(savedInstanceState==null)intent.getStringExtra("alert") else savedInstanceState.getString("focused_alert")
        targetHandled=savedInstanceState?.getBoolean("target_handled")?:false
        supportActionBar?.apply { title="Class alerts";setDisplayHomeAsUpEnabled(true) }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16) }
        status=TextView(this).also { content.addView(it) }
        classes=Spinner(this).also { content.addView(it) }
        classes.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) { val c=classIds.getOrNull(position) ?: return; if(model.state.value.session?.selectedClass!=c) {focusedAlert=null;model.select(c)} }
        }
        fun button(text:String,action:()->Unit) { content.addView(Button(this).apply { this.text=text;setOnClickListener { action() } }) }
        button("Refresh / retry connection") { model.refresh() }
        button("Compose alert") { model.draft(); showEditor() }
        button("Hidden / visible") { hidden=!hidden;render(model.state.value) }
        button("Active / personal trash") { trash=!trash;render(model.state.value) }
        button("Active / archive") { archive=!archive;render(model.state.value) }
        button("Show all class alerts") { focusedAlert=null;render(model.state.value) }
        button("Map attendance subject badges") { mapSubjects() }
        button("Request class membership") { requestMembership() }
        button("Legacy alerts (read only)") { startActivity(Intent(this,com.mit.attendance.ui.alerts.AlertsActivity::class.java).putExtra("legacy",true)) }
        list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };content.addView(list)
        setContentView(ScrollView(this).apply { addView(content) })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { model.state.collect { render(it) } }
            finally { socket?.cancel(); socketGeneration=null }
        } }
    }
    override fun onStart() {
        super.onStart()
        val expected=model.state.value.session
        if(expected!=null&&CampusSessionStore(this).read()?.generation!=expected.generation){model.clearForAccountChange();finish()}
    }
    private fun render(state:AlertsScreen) {
        if(state.draft==null&&editor!=null)editor?.dismiss()
        editor?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=!state.saving
        editor?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled=!state.saving
        status.text=if(state.busy) "Synchronizing…" else state.error
        val ids=state.classes.mapNotNull { it.string("id") }
        if(ids!=classIds) {classIds=ids;classes.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,state.classes.map { it.string("display_name") ?: "Class" })}
        state.session?.selectedClass?.let { val position=classIds.indexOf(it);if(position>=0&&classes.selectedItemPosition!=position)classes.setSelection(position) }
        list.removeAllViews()
        val session=state.session ?: return
        if(intent.getStringExtra("account")?.let { it!=session.profile.id }==true){status.text="This link belongs to another campus account.";return}
        if(socketGeneration!=session.generation&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){
            socket?.cancel();socketGeneration=session.generation
            socket=lifecycleScope.launch {CampusSocket(CampusSessionStore(this@CampusAlertsActivity),this@CampusAlertsActivity).run(session){
                runCatching {val alerts=model.repository.sync(CampusSessionStore(this@CampusAlertsActivity).read()?:session);for(a in alerts)CampusNotifications.show(this@CampusAlertsActivity,session,a.classId,a.id,a.version)}
            }}
        }
        if(!targetHandled&&!state.busy&&state.error.isBlank()&&intent.getStringExtra("class")!=null) {
            targetHandled=true;model.target(intent.getStringExtra("class")!!,intent.getStringExtra("alert"))
        }
        val currentClass=state.classes.firstOrNull { it.string("id")==session.selectedClass }
        val rep=currentClass?.string("role")=="REPRESENTATIVE"
        for(pending in state.outgoing) {
            val card=card();card.addView(TextView(this).apply { text="${pending.status}: ${pending.method} alert\n${pending.error}\n${runCatching { JsonParser.parseString(pending.body).asJsonObject.getAsJsonObject("content")?.string("topic") }.getOrNull() ?: pending.resourceId}" })
            addButton(card,"Retry") {model.pending(pending,false)};addButton(card,"Cancel pending change") {model.pending(pending,true)}
            if(pending.status=="CONFLICT") addButton(card,"Resolve with current alert") {
                val original=state.alerts.firstOrNull { it.id==pending.resourceId } ?: return@addButton
                model.draft(original)
                runCatching { JsonParser.parseString(pending.body).asJsonObject.getAsJsonObject("content") }.getOrNull()?.let {model.updateDraft(it.deepCopy())}
                showEditor() // Original failed draft stays in the queue until explicitly cancelled.
            }
        }
        val filterName=intent.getStringExtra("extra_filter_subject")
        renderJob?.cancel()
        renderJob=lifecycleScope.launch {
            val mapped=if(filterName!=null) model.repository.dao.links(session.profile.id,session.selectedClass?:"").firstOrNull {it.localName==filterName}?.subjectId else null
            if(model.state.value.session?.generation!=session.generation) return@launch
            for(a in state.alerts) {
                if(focusedAlert!=null&&a.id!=focusedAlert)continue
                val personal=state.personal.firstOrNull {it.alertId==a.id}
                if(focusedAlert==null) {
                    if(filterName!=null && a.subjectId!=mapped) continue
                    if((personal?.hidden==true)!=hidden || (personal?.trashed==true)!=trash || (!trash && (a.status=="EXPIRED")!=archive)) continue
                }
                val card=card()
                card.addView(TextView(this@CampusAlertsActivity).apply { text="${a.topic}\n${a.authorName} · ${a.type} · ${a.status}\n${a.description}\nDue: ${a.dueAt ?: "—"}";textSize=16f;setOnClickListener {model.personal(a,seen=true)} })
                addButton(card,if(trash) "Restore personal trash" else "Move to personal trash") {model.personal(a,trashed=!trash)}
                addButton(card,if(hidden) "Unhide for me" else "Hide for me") {model.personal(a,hidden=!hidden)}
                if(rep||a.authorId==session.profile.id) {
                    addButton(card,"Edit") {model.draft(a);showEditor()}
                    addButton(card,"Delete for everyone") {AlertDialog.Builder(this@CampusAlertsActivity).setMessage("Delete ${a.topic} for everyone?").setPositiveButton("Delete") {_,_->model.delete(a)}.setNegativeButton("Cancel",null).show()}
                }
                if(a.status=="PUBLISHED")addButton(card,"Set local reminder") {reminder(session,a)}
            }
        }
        if(state.draft!=null&&editor==null)showEditor()
    }
    private fun card():LinearLayout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(0,20,0,20);list.addView(this)}
    private fun addButton(parent:LinearLayout,label:String,action:()->Unit) {parent.addView(Button(this).apply {text=label;setOnClickListener {action()} })}
    private fun showEditor() {
        if(editor!=null)return
        val state=model.state.value;val draft=state.draft ?: return
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        fun field(key:String,label:String,default:String=""):EditText=EditText(this).apply {
            hint=label;setText(draft.string(key)?:default);isSaveEnabled=false
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { val copy=model.state.value.draft?.deepCopy()?:return;copy.addProperty(key,s?.toString());model.updateDraft(copy) }
                override fun afterTextChanged(s:android.text.Editable?) {}
            });layout.addView(this)
        }
        field("topic","Topic");field("description","Description")
        val types=listOf("NOTICE","HOMEWORK","EXAM","ASSIGNMENT","OTHER")
        val type=Spinner(this);layout.addView(type)
        type.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,types.map {it.lowercase().replaceFirstChar(Char::uppercaseChar)})
        type.setSelection(types.indexOf(draft.string("type")).coerceAtLeast(0))
        type.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {val copy=model.state.value.draft?.deepCopy()?:return;copy.addProperty("type",types[position]);model.updateDraft(copy)}
        }
        val subjects=state.subjects
        val spinner=Spinner(this);layout.addView(spinner)
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Class-wide")+subjects.map {it.string("name")?:"Subject"})
        spinner.setSelection(subjects.indexOfFirst {it.string("id")==draft.string("subject_id")}.let {if(it<0)0 else it+1})
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {val copy=model.state.value.draft?.deepCopy()?:return;copy.addProperty("subject_id",subjects.getOrNull(position-1)?.string("id"));model.updateDraft(copy)}
        }
        val zone=ZoneId.of(state.classes.firstOrNull {it.string("id")==state.session?.selectedClass}?.string("timezone")?:"Asia/Kolkata")
        fun dateField(key:String,label:String) {
            val row=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};layout.addView(row)
            val choose=Button(this);row.addView(choose)
            fun caption(){val value=model.state.value.draft?.string(key);choose.text="$label ($zone): "+(value?.let {runCatching {Instant.parse(it).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))}.getOrNull()}?:"Choose time")}
            caption()
            choose.setOnClickListener {
                val selected=model.state.value.draft?.string(key)?.let {runCatching {Instant.parse(it).atZone(zone)}.getOrNull()}?:ZonedDateTime.now(zone)
                DatePickerDialog(this,{_,year,month,day->TimePickerDialog(this,{_,hour,minute->
                    val copy=model.state.value.draft?.deepCopy()?:return@TimePickerDialog
                    copy.addProperty(key,LocalDateTime.of(year,month+1,day,hour,minute).atZone(zone).toInstant().toString())
                    if(key=="publish_at")copy.addProperty("draft",false)
                    model.updateDraft(copy);caption()
                },selected.hour,selected.minute,true).show()},selected.year,selected.monthValue-1,selected.dayOfMonth).show()
            }
            addButton(row,"Clear $label") {val copy=model.state.value.draft?.deepCopy()?:return@addButton;copy.add(key,JsonNull.INSTANCE);model.updateDraft(copy);caption()}
        }
        dateField("due_at","Due");dateField("expires_at","Expiry")
        if(state.classes.any {it.string("id")==state.session?.selectedClass&&it.string("role")=="REPRESENTATIVE"}&&state.editing?.status !in listOf("PUBLISHED","EXPIRED"))dateField("publish_at","Scheduled publication")
        addButton(layout,"Preview") {val preview=model.state.value.draft;AlertDialog.Builder(this).setTitle(preview?.string("topic")).setMessage(preview?.string("description")).setPositiveButton("Back",null).show()}
        layout.addView(CheckBox(this).apply {text="Private draft";isChecked=draft.get("draft")?.asBoolean==true;setOnCheckedChangeListener {_,checked->val copy=model.state.value.draft?.deepCopy()?:return@setOnCheckedChangeListener;copy.addProperty("draft",checked);model.updateDraft(copy)} })
        val dialog=AlertDialog.Builder(this).setTitle("Compose / edit alert").setView(ScrollView(this).apply {addView(layout)}).setPositiveButton("Queue save",null).setNegativeButton("Cancel") {_,_->model.closeDraft()}.create()
        editor=dialog;dialog.setOnDismissListener {editor=null};dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val copy=model.state.value.draft?.deepCopy()?:return@setOnClickListener
            try {
                require(!copy.string("topic").isNullOrBlank()) {"Enter a topic."}
                for(key in listOf("due_at","expires_at","publish_at")) {val value=copy.string(key);if(value.isNullOrBlank())copy.add(key,com.google.gson.JsonNull.INSTANCE)else OffsetDateTime.parse(value)}
                model.updateDraft(copy);model.save()
            }catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}
        }};dialog.show()
    }
    private fun requestMembership() {
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        val input=EditText(this).apply {hint="Class ID from representative";layout.addView(this)}
        val invite=EditText(this).apply {hint="Invite ID (optional)";layout.addView(this)}
        val secret=EditText(this).apply {hint="Invite secret (optional)";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;layout.addView(this)}
        val dialog=AlertDialog.Builder(this).setTitle("Join a class").setView(layout).setPositiveButton("Request access",null).setNegativeButton("Cancel",null).create()
        var key=UUID.randomUUID().toString()
        var previousBody:String?=null
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {model.action {
            val session=model.state.value.session ?: CampusRepository(this).refresh()
            val id=input.text.toString().trim();require(id.matches(Regex("[A-Za-z0-9-]+")))
            val inviteId=invite.text.toString().trim();val inviteSecret=secret.text.toString()
            require(inviteId.isBlank()==inviteSecret.isBlank()) {"Enter both the invite ID and secret, or leave both empty."}
            val body=jsonObject()
            if(inviteId.isNotBlank()){body.addProperty("invite_id",inviteId);body.addProperty("invite_secret",inviteSecret)}
            val content="$id:$body"
            if(previousBody!=null&&previousBody!=content)key=UUID.randomUUID().toString()
            previousBody=content
            body.addProperty("idempotency_key",key)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            try {
                val result=CampusTransport.authenticated(this@CampusAlertsActivity,session).request("v1/classes/$id/join-requests","POST",body.toString()).asJsonObject
                secret.text.clear();dialog.dismiss()
                Toast.makeText(this,if(result.string("state")=="APPROVED")"You joined the class." else "Request sent for representative approval.",Toast.LENGTH_LONG).show()
                model.refresh()
            } finally {dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}
        }}}
        dialog.show()
    }
    private fun mapSubjects() {model.action {
        val session=model.state.value.session ?: return@action;val c=session.selectedClass ?: return@action
        val local=model.repository.db.subjectDao().getAllSubjectsList()
        AlertDialog.Builder(this).setTitle("Choose attendance subject").setItems(local.map {it.subjectName}.toTypedArray()) {_,position->
            val subjects=model.state.value.subjects
            AlertDialog.Builder(this).setTitle("Match server subject").setItems(subjects.map {it.string("name")?:"Subject"}.toTypedArray()) {_,server->model.action {model.repository.dao.link(CampusSubjectLink(session.profile.id,c,local[position].subjectName,subjects[server].string("id")!!))}}.show()
        }.show()
    }}
    private fun reminder(session:CampusSession,alert:CachedCampusAlert) {
        val date=Calendar.getInstance()
        DatePickerDialog(this,{_,y,m,d->date.set(y,m,d);TimePickerDialog(this,{_,h,min->date.set(Calendar.HOUR_OF_DAY,h);date.set(Calendar.MINUTE,min);date.set(Calendar.SECOND,0);runCatching {CampusReminders.schedule(this,session,alert,date.timeInMillis)}.onFailure {Toast.makeText(this,it.message,Toast.LENGTH_LONG).show()}},date.get(Calendar.HOUR_OF_DAY),date.get(Calendar.MINUTE),true).show()},date.get(Calendar.YEAR),date.get(Calendar.MONTH),date.get(Calendar.DAY_OF_MONTH)).show()
    }
    override fun onSupportNavigateUp():Boolean {finish();return true}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("focused_alert",focusedAlert)
        outState.putBoolean("target_handled",targetHandled)
        super.onSaveInstanceState(outState)
    }
}
