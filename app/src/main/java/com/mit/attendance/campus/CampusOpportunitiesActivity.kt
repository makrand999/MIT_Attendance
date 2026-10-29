package com.mit.attendance.campus

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import androidx.room.withTransaction
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class OpportunitiesState(val session:CampusSession?=null,val section:String="jobs",val id:String="",val jobId:String="",val title:String="Opportunities",
    val items:List<JsonObject> = emptyList(),val detail:JsonObject?=null,val busy:Boolean=false,val error:String="",val offset:Int=0,val more:Boolean=false,val filters:Map<String,String> = emptyMap(),val draft:JsonObject?=null,val outgoing:List<CampusOutgoing> = emptyList(),val sending:Boolean=false)
internal class OpportunitiesViewModel(app:Application):AndroidViewModel(app) {
    private val auth=CampusRepository(app)
    private val repo=AkatsukiRepository(app)
    private val mutable=MutableStateFlow(OpportunitiesState())
    val state=mutable.asStateFlow()
    private var request:Job?=null
    private val activeSends=mutableSetOf<String>()
    fun clearForAccountChange(){request?.cancel();mutable.value=OpportunitiesState(error="Campus account changed. Refresh to reconnect.")}
    fun open(section:String,id:String="",job:String="") {request?.cancel();mutable.update {it.copy(section=section,id=id,jobId=job,items=emptyList(),detail=null,offset=0,more=false,error="",draft=if(it.section==section&&it.id==id)it.draft else null)};load()}
    fun filters(values:Map<String,String>) {mutable.update {it.copy(filters=values,offset=0,items=emptyList())};load()}
    fun action(block:suspend ()->Unit) {viewModelScope.launch {try{block()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update {it.copy(error=e.message?:"Campus request failed.",busy=false)}}}}
    fun load(next:Boolean=false) {
        request?.cancel()
        request=viewModelScope.launch {
            mutable.update {it.copy(busy=true,error="")}
            try {
                val s=mutable.value
                val session=auth.cached()?:auth.refresh()
                repo.assertCurrent(session)
                mutable.update {it.copy(session=session,draft=if(it.session?.profile?.id!=null&&it.session.profile.id!=session.profile.id)null else it.draft)}
                val offset=if(next)s.offset else 0
                val path=when(s.section){
                    "jobs"->"jobs";"mine"->"jobs/my/posted";"job","editor"->"jobs/${s.id}"
                    "applications"->"applications/my";"applicants"->"jobs/${s.id}/applications"
                    "application"->"applications/my"
                    "conversations"->if(s.jobId.isBlank())"conversations" else "jobs/${s.jobId}/conversations"
                    "messages"->"conversations/${s.id}/messages";else->error("Unknown campus page")
                }
                val cacheKind="opportunities:${s.section}"
                val parent="${s.id}:${s.jobId}:${s.filters.entries.sortedBy{it.key}}"
                if(!next){
                    val cached=repo.dao.directory(session.profile.id,cacheKind,parent).map{JsonParser.parseString(it.json).asJsonObject}
                        .let{if(s.section=="messages")it.sortedWith(compareBy({row->row.string("created_at")},{row->row.string("id")}))else it}
                    mutable.update {it.copy(items=cached,detail=if(s.section in listOf("job","editor","application"))cached.firstOrNull() else null)}
                }
                if(s.section=="editor"&&s.id.isBlank()){mutable.update{it.copy(busy=false)};return@launch}
                val filters=if(s.section=="jobs")s.filters else emptyMap()
                val query=(filters+mapOf("limit" to "50","offset" to offset.toString())).entries.joinToString("&"){"${it.key}=${java.net.URLEncoder.encode(it.value,"UTF-8")}"}
                val result=if(s.section=="application")repo.application(session,s.id,s.jobId)
                    else CampusTransport.authenticated(getApplication(),session).request(path+if(s.section in listOf("job","editor"))"" else "?$query")
                repo.assertCurrent(session)
                val rows=if(result.isJsonArray)result.asJsonArray.map{it.asJsonObject}else listOf(result.asJsonObject)
                val all=if(next||s.section=="messages") (mutable.value.items+rows).associateBy{it.string("id")}.values.toList() else rows
                val ordered=if(s.section=="messages")all.sortedWith(compareBy({it.string("created_at")},{it.string("id")}))else all
                repo.db.withTransaction {
                    repo.assertCurrent(session)
                    if(!next&&s.section!="messages")repo.dao.clearDirectory(session.profile.id,cacheKind,parent)
                    repo.dao.saveDirectory(rows.map{CampusDirectory(session.profile.id,cacheKind,parent,it.string("id")!!,it.toString())})
                    repo.assertCurrent(session)
                }
                val outgoing=repo.dao.outgoing(session.profile.id,"__messages").filter{p->p.resourceId==s.id}.map{item->
                    if(item.key !in activeSends&&item.status in listOf("SENDING","PENDING")) {
                        item.copy(status="UNCERTAIN",error="Send was interrupted. Refresh this conversation before sending again.").also{repo.dao.saveOutgoing(it)}
                    } else item
                }
                mutable.update{it.copy(session=repo.store.read() ?: session,items=ordered,detail=if(s.section in listOf("job","editor","application"))rows.firstOrNull()else null,busy=false,offset=offset+rows.size,more=result.isJsonArray&&rows.size==50,outgoing=outgoing)}
            } catch(e:CancellationException){throw e}catch(e:Exception){
                if(e is CampusFailure && e.status==401)CampusSessionStore(getApplication()).invalidate(sessionOrGeneration())
                val denied=e is CampusFailure&&e.status in listOf(403,404)
                mutable.update{it.copy(error=e.message?:"Could not load campus.",busy=false,items=if(denied)emptyList()else it.items,detail=if(denied)null else it.detail)}
            }
        }
    }
    private fun sessionOrGeneration():String = mutable.value.session?.generation ?: ""
    suspend fun mutate(path:String,method:String="POST",body:JsonObject=jsonObject(),expectedAccount:String?=null):JsonElement {
        val session=mutable.value.session?:auth.refresh();repo.assertCurrent(session)
        check(expectedAccount==null||expectedAccount==session.profile.id) {"This action belongs to a different campus account."}
        val response=CampusTransport.authenticated(getApplication(),session).request(path,method,body.toString());repo.assertCurrent(session);return response
    }
    fun draft(value:JsonObject?) {mutable.update{it.copy(draft=value)}}
    fun send(content:String,expectedAccount:String?,expectedConversation:String)=action {
        if(mutable.value.sending)return@action
        require(content.isNotBlank()&&content.toByteArray().size<=4000){"Enter a message up to 4,000 bytes."}
        val s=mutable.value;val session=s.session?:error("Connect to Campus first.")
        check(s.section=="messages"&&s.id==expectedConversation){"This draft belongs to another conversation."}
        check(session.profile.id==expectedAccount){"This draft belongs to another campus account."}
        val key=UUID.randomUUID().toString()
        val item=CampusOutgoing(key,session.profile.id,"__messages",s.id,"POST","conversations/${s.id}/messages",jsonObject("content" to content).toString(),status="SENDING")
        mutable.update{it.copy(sending=true)}
        try {
            repo.db.withTransaction{repo.assertCurrent(session);repo.dao.enqueue(item);repo.assertCurrent(session)}
            mutable.update{if(it.section=="messages"&&it.id==item.resourceId)it.copy(outgoing=it.outgoing+item,draft=null)else it}
            deliver(item)
        } finally {mutable.update{it.copy(sending=false)}}
    }
    fun retry(item:CampusOutgoing,cancel:Boolean)=action{
        val session=mutable.value.session?:return@action
        repo.assertCurrent(session)
        check(item.account==session.profile.id)
        check(item.key !in activeSends){"This message is being sent."}
        if(cancel){repo.dao.removeOutgoing(item.account,item.key);mutable.update{it.copy(outgoing=it.outgoing.filter{p->p.key!=item.key})}}
        else deliver(item)
    }
    private suspend fun deliver(item:CampusOutgoing){
        if(!activeSends.add(item.key))return
        try{
            val sessionBefore=mutable.value.session?:return
            repo.db.withTransaction{repo.assertCurrent(sessionBefore);check(item.account==sessionBefore.profile.id);repo.dao.saveOutgoing(item.copy(status="SENDING",error=""));repo.assertCurrent(sessionBefore)}
            // The deployed message endpoint accepts content and has no retry-key contract.
            val body=jsonObject("content" to JsonParser.parseString(item.body).asJsonObject.string("content"))
            mutate(item.path,item.method,body,expectedAccount=item.account)
            val session=mutable.value.session?:return
            repo.db.withTransaction{repo.assertCurrent(session);check(session.profile.id==item.account);repo.dao.removeOutgoing(item.account,item.key);repo.assertCurrent(session)}
            mutable.update{it.copy(outgoing=it.outgoing.filter{p->p.key!=item.key})}
            load()
        }catch(e:CancellationException){throw e}catch(e:Exception){
            val session=mutable.value.session?:return
            if(session.profile.id!=item.account)return
            repo.assertCurrent(session)
            val uncertain=e is java.io.IOException&&(e !is CampusFailure||e.status>=500||e.status==408)
            val failed=item.copy(status=if(uncertain)"UNCERTAIN" else "FAILED",error=if(uncertain)"Couldn't confirm this send. Refresh the conversation before sending again." else e.message?:"Send failed. Retry when connected.")
            repo.dao.saveOutgoing(failed);mutable.update{if(it.section=="messages"&&it.id==item.resourceId)it.copy(outgoing=it.outgoing.filter{p->p.key!=item.key}+failed,error=failed.error)else it}
        } finally {activeSends.remove(item.key)}
    }
}

class CampusOpportunitiesActivity:AppCompatActivity(){
    private val categories=listOf("INTERNSHIP","PART_TIME","RESEARCH_ASSISTANT","PROJECT_COLLAB","CAMPUS_GIG","TUTORING")
    private val locations=listOf("ON_CAMPUS","REMOTE","HYBRID")
    private val model:OpportunitiesViewModel by viewModels()
    private lateinit var root:LinearLayout
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private var editor:AlertDialog?=null
    private var socket:Job?=null
    private var socketGeneration:String?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        supportActionBar?.apply{title="Akatsuki";setDisplayHomeAsUpEnabled(true)}
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        status=TextView(this);root.addView(status)
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};root.addView(HorizontalScrollView(this).apply{addView(nav)})
        for((name,section)in listOf("Explore" to "jobs","My applications" to "applications","My listings" to "mine","Inbox" to "conversations"))button(nav,name){model.open(section)}
        button(root,"Refresh"){model.load()};button(root,"Filters"){filters()};button(root,"Post opportunity"){model.open("editor");model.draft(jsonObject());showJobEditor()}
        button(root,"Open browser workspace"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(CampusHttp.WEB_WORKSPACE)))}
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(body)
        setContentView(ScrollView(this).apply{addView(root)})
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){
            launch{model.state.collect{render(it)}}
            try{awaitCancellation()}finally{socket?.cancel();socketGeneration=null}
        }}
        if(savedInstanceState==null||model.state.value.session==null)model.open(intent.getStringExtra("section")?:"jobs",intent.getStringExtra("id")?:"",intent.getStringExtra("job")?:"")
    }
    override fun onStart(){super.onStart();val expected=model.state.value.session;if(expected!=null&&CampusSessionStore(this).read()?.generation!=expected.generation){model.clearForAccountChange();finish()}}
    private fun button(parent:LinearLayout,text:String,action:()->Unit){parent.addView(Button(this).apply{this.text=text;setOnClickListener{action()}})}
    private fun card(text:String):LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,20,0,20);addView(TextView(this@CampusOpportunitiesActivity).apply{this.text=text;textSize=16f});body.addView(this)}
    private fun render(s:OpportunitiesState){
        status.text=if(s.busy)"Loading…" else  s.error
        body.removeAllViews()
        val session=s.session?:return
        val expected=intent.getStringExtra("account")
        if(expected!=null&&expected!=session.profile.id){status.text="This link belongs to another campus account.";return}
        if(socketGeneration!=session.generation&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){socket?.cancel();socketGeneration=session.generation;socket=lifecycleScope.launch{CampusSocket(CampusSessionStore(this@CampusOpportunitiesActivity),this@CampusOpportunitiesActivity).run(session){model.load()}}}
        if(s.section=="job"){
            val job=s.detail?:return
            val id=job.string("id")?:return
            val card=card("${job.string("title")}\n${job.string("status")} · ${job.string("category")} · ${job.string("location_type")}\n${job.string("location")?:""}\n${job.string("stipend")?:""}\nDeadline: ${job.string("deadline")?:"—"}\n${job.string("description")?:""}")
            images(card,session,job.string("description")?:"")
            if(job.string("creator_id")==session.profile.id){
                button(card,"Edit listing"){if(model.state.value.draft?.string("id")!=id)model.draft(job.deepCopy());showJobEditor()}
                button(card,if(job.string("status")=="OPEN")"Close listing" else "Reopen listing"){model.action{model.mutate("jobs/$id/status","PATCH",jsonObject("status" to if(job.string("status")=="OPEN")"CLOSED" else "OPEN"));model.load()}}
                button(card,"Applicants"){model.open("applicants",id)}
                button(card,"Job conversations"){model.open("conversations",job=id)}
            }else{
                button(card,"Apply"){if(job.string("status")=="OPEN")apply(id)else Toast.makeText(this,"This opportunity is closed.",Toast.LENGTH_LONG).show()}
                button(card,"Open job conversation"){model.action{val conv=model.mutate("conversations/job/$id").asJsonObject;model.open("messages",conv.string("id")!!,id)}}
            }
        }else if(s.section=="application"){
            val application=s.detail?:return
            val id=application.string("id")?:return
            val c=card("${application.string("job_title")}\n${application.string("applicant_name")} · ${application.string("status")}\n${application.string("pitch_note")?:""}")
            application.string("resume_url")?.let{url->button(c,"Open resume"){openLink(url)}}
            button(c,"View opportunity"){model.open("job",application.string("job_id")!!)}
            if(application.get("can_manage")?.asBoolean==true)button(c,"Change application status"){
                val choices=arrayOf("PENDING","SHORTLISTED","ACCEPTED","REJECTED")
                AlertDialog.Builder(this).setItems(choices){_,position->model.action{
                    model.mutate("applications/$id/status","PATCH",jsonObject("status" to choices[position]));model.load()
                }}.show()
            }
        }else if(s.section=="messages"){
            card("Private conversation")
            s.items.forEach{message->card("${message.string("sender_name")} · ${message.string("created_at")}\n${message.string("content")}")}
            s.outgoing.forEach{item->
                val c=card("${if(item.status=="SENDING")"Sending…" else if(item.status=="UNCERTAIN")"Send not confirmed" else "Message not sent"}\n${JsonParser.parseString(item.body).asJsonObject.string("content")}\n${item.error}")
                if(item.status!="SENDING") {
                    button(c,"Retry send"){
                        if(item.status=="UNCERTAIN")AlertDialog.Builder(this).setTitle("Send this message again?")
                            .setMessage("The earlier send may have arrived. Check the conversation first to avoid sending it twice.")
                            .setPositiveButton("Send again"){_,_->model.retry(item,false)}.setNegativeButton("Cancel",null).show()
                        else model.retry(item,false)
                    }
                    button(c,"Dismiss"){model.retry(item,true)}
                }
            }
            if(!s.sending)button(body,"Write message"){message()}
        }else if(s.section=="editor"){
            button(body,"Open listing editor"){if(s.draft==null)model.draft(s.detail?:jsonObject());showJobEditor()}
        }else for(item in s.items){
            val id=item.string("id")?:continue
            val c=card(listOfNotNull(item.string("title")?:item.string("job_title")?:item.string("applicant_name")?:item.string("student_name"),item.string("status"),item.string("pitch_note"),item.string("last_message"),item.string("unread_count")?.let{"Unread: $it"}).joinToString("\n"))
            when(s.section){
                "jobs","mine"->button(c,"View opportunity"){model.open("job",id)}
                "applications"->button(c,"View application"){model.open("application",id)}
                "applicants"->{
                    button(c,"View application"){model.open("application",id,s.id)}
                    item.string("resume_url")?.let{url->button(c,"Open resume"){openLink(url)}}
                    button(c,"Change application status"){AlertDialog.Builder(this).setItems(arrayOf("PENDING","SHORTLISTED","ACCEPTED","REJECTED")){_,position->model.action{model.mutate("applications/$id/status","PATCH",jsonObject("status" to arrayOf("PENDING","SHORTLISTED","ACCEPTED","REJECTED")[position]));model.load()}}.show()}
                }
                "conversations"->button(c,"Open private conversation"){model.open("messages",id,item.string("job_id")?:s.jobId)}
            }
        }
        if(s.more)button(body,if(s.section=="messages")"Load older messages" else "Load more"){model.load(true)}
    }
    private fun openLink(url:String){val uri=Uri.parse(url);if(uri.scheme in listOf("https","http"))startActivity(Intent(Intent.ACTION_VIEW,uri))}
    private fun choice(parent:LinearLayout,label:String,options:List<String>,selected:String?):Spinner {
        parent.addView(TextView(this).apply{text=label})
        return Spinner(this).apply {
            adapter=ArrayAdapter(this@CampusOpportunitiesActivity,android.R.layout.simple_spinner_dropdown_item,
                options.map{if(it.isEmpty())"Any" else it.lowercase().replace('_',' ').replaceFirstChar(Char::uppercaseChar)})
            setSelection(options.indexOf(selected).coerceAtLeast(0));parent.addView(this)
        }
    }
    private fun filters(){
        val fields=listOf("search","department")
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val inputs=fields.associateWith{key->EditText(this).apply{hint=key;setText(model.state.value.filters[key]?:"");layout.addView(this)}}
        val options=mapOf("category" to listOf("")+categories,"location_type" to listOf("")+locations,"status" to listOf("OPEN","CLOSED"))
        val choices=options.mapValues{(key,values)->choice(layout,key.replace('_',' '),values,model.state.value.filters[key])}
        AlertDialog.Builder(this).setTitle("Opportunity filters").setView(ScrollView(this).apply{addView(layout)}).setPositiveButton("Apply"){_,_->
            val selected=inputs.mapValues{it.value.text.toString().trim()}+choices.mapValues{(key,input)->options.getValue(key)[input.selectedItemPosition]}
            model.filters(selected.filterValues{it.isNotEmpty()})
        }.setNegativeButton("Cancel",null).show()
    }
    private fun apply(id:String){
        val expected=model.state.value.session?.profile?.id
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val previous=model.state.value.draft?.takeIf {it.string("apply_job")==id}
        val pitch=EditText(this).apply{hint="Your pitch";setText(previous?.string("pitch_note")?:"");layout.addView(this)}
        val resume=EditText(this).apply{hint="Resume URL (optional)";setText(previous?.string("resume_url")?:"");layout.addView(this)}
        val watcher=object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun afterTextChanged(s:android.text.Editable?){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){model.draft(jsonObject("apply_job" to id,"pitch_note" to pitch.text.toString(),"resume_url" to resume.text.toString()))}
        }
        pitch.addTextChangedListener(watcher);resume.addTextChangedListener(watcher)
        val dialog=AlertDialog.Builder(this).setTitle("Apply to opportunity").setView(layout).setPositiveButton("Submit",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{model.action{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            try {
                require(pitch.text.toString().isNotBlank()){"Enter a short pitch for your application."}
                model.mutate("jobs/$id/apply",body=jsonObject("pitch_note" to pitch.text.toString(),"resume_url" to resume.text.toString().trim().takeIf{it.isNotBlank()}),expectedAccount=expected)
                model.draft(null);dialog.dismiss();model.open("applications")
            }catch(e:CancellationException){throw e}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show();throw e}
            finally {dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}
        }}};dialog.show()
    }
    private fun message(){
        val expected=model.state.value.session?.profile?.id
        val conversation=model.state.value.id
        val input=EditText(this).apply{hint="Message";setText(model.state.value.draft?.string("message")?:"")}
        input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:android.text.Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){model.draft(jsonObject("message" to s?.toString()))}})
        AlertDialog.Builder(this).setTitle("Send message").setView(input).setPositiveButton("Send"){_,_->model.send(input.text.toString(),expected,conversation)}.setNegativeButton("Keep draft",null).show()
    }
    private fun showJobEditor(){
        if(editor!=null)return
        val initial=model.state.value.draft?:return
        val expected=model.state.value.session?.profile?.id ?: return
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10)}
        val fields=listOf("title","description","location","stipend","skills_required","department","deadline")
        val values=fields.associateWith{key->EditText(this).apply{hint=key.replace('_',' ')+(if(key=="deadline")" (YYYY-MM-DD)" else "");setText(initial.string(key)?:"");isSaveEnabled=false;layout.addView(this)}}
        val choices=mapOf("category" to (categories to choice(layout,"Category",categories,initial.string("category")?:"CAMPUS_GIG")),
            "location_type" to (locations to choice(layout,"Location type",locations,initial.string("location_type")?:"ON_CAMPUS")))
        choices.forEach{(key,pair)->pair.second.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long){
                val draft=model.state.value.draft?.deepCopy()?:return;draft.addProperty(key,pair.first[position]);model.draft(draft)
            }
        }}
        for((key,input)in values)input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:android.text.Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){val draft=model.state.value.draft?.deepCopy()?:jsonObject();draft.addProperty(key,s?.toString());model.draft(draft)}})
        val dialog=AlertDialog.Builder(this).setTitle("Opportunity listing").setView(ScrollView(this).apply{addView(layout)}).setPositiveButton("Save",null).setNegativeButton("Keep draft",null).create()
        editor=dialog;dialog.setOnDismissListener{editor=null};dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{model.action{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            try {
                require(values.getValue("title").text.toString().isNotBlank()){"Enter an opportunity title."}
                require(values.getValue("description").text.toString().isNotBlank()){"Enter an opportunity description."}
                val body=jsonObject();for((key,input)in values)body.addProperty(key,input.text.toString().trim().takeIf{it.isNotBlank()})
                for((key,pair)in choices)body.addProperty(key,pair.first[pair.second.selectedItemPosition])
                body.string("deadline")?.let{java.time.LocalDate.parse(it)}
                val id=initial.string("id")
                val result=model.mutate(if(id==null)"jobs" else "jobs/$id",if(id==null)"POST" else "PUT",body,expectedAccount=expected).asJsonObject
                model.draft(null);dialog.dismiss();model.open("job",result.string("id")?:id!!)
            }catch(e:CancellationException){throw e}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show();throw e}
            finally {dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}
        }}};dialog.show()
    }
    private fun images(parent:LinearLayout,session:CampusSession,description:String){
        val ids=Regex("!\\[[^]]*]\\((?:/[^?#() ]*)?/api/images/([a-f0-9-]{36})\\)").findAll(description).map{it.groupValues[1]}.distinct().take(10)
        for(id in ids){val image=ImageView(this).apply{adjustViewBounds=true;contentDescription="Opportunity description image"};parent.addView(image);lifecycleScope.launch{
            runCatching{val bytes=CampusTransport.authenticated(this@CampusOpportunitiesActivity,session).image(id);withContext(Dispatchers.Default){BitmapFactory.decodeByteArray(bytes,0,bytes.size)}}.onSuccess{if(CampusSessionStore(this@CampusOpportunitiesActivity).read()?.generation==session.generation&&model.state.value.section=="job")image.setImageBitmap(it)}
        }}
    }
    override fun onSupportNavigateUp():Boolean{finish();return true}
}
