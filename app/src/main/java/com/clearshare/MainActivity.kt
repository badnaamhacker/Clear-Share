package com.clearshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.time.LocalDate

@Entity
data class RoomGroup(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)
@Entity(foreignKeys = [ForeignKey(entity = RoomGroup::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)])
data class Member(@PrimaryKey(autoGenerate = true) val id: Long = 0, val roomId: Long, val name: String)
@Entity(foreignKeys = [ForeignKey(entity = RoomGroup::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)])
data class Expense(@PrimaryKey(autoGenerate = true) val id: Long = 0, val roomId: Long, val title: String, val amount: Double, val payerId: Long, val splitMemberIds: String, val category: String, val date: String = LocalDate.now().toString())

@Dao interface Dao {
    @Query("SELECT * FROM RoomGroup") fun rooms(): Flow<List<RoomGroup>>
    @Insert suspend fun addRoom(room: RoomGroup): Long
    @Query("SELECT * FROM Member WHERE roomId=:roomId") fun members(roomId: Long): Flow<List<Member>>
    @Insert suspend fun addMember(member: Member)
    @Delete suspend fun delMember(member: Member)
    @Query("SELECT COUNT(*) FROM Member WHERE roomId=:roomId AND name=:name") suspend fun duplicate(roomId: Long, name: String): Int
    @Query("SELECT * FROM Expense WHERE roomId=:roomId") fun expenses(roomId: Long): Flow<List<Expense>>
    @Insert suspend fun addExpense(expense: Expense)
}

@Database(entities = [RoomGroup::class, Member::class, Expense::class], version = 1)
abstract class AppDb: RoomDatabase(){ abstract fun dao(): Dao }

class MainVm(private val dao: Dao): ViewModel() {
    val rooms = dao.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var selectedRoomId by mutableStateOf<Long?>(null)
    val showCreateFirstRoom = rooms.map { it.isEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun pickLastRoom(){ if (selectedRoomId==null && rooms.value.isNotEmpty()) selectedRoomId = rooms.value.last().id }
    fun createRoom(name: String){ if (name.isBlank()) return; viewModelScope.launch { selectedRoomId = dao.addRoom(RoomGroup(name=name.trim())) } }
    fun membersFlow() = selectedRoomId?.let { dao.members(it) } ?: flowOf(emptyList())
    fun expensesFlow() = selectedRoomId?.let { dao.expenses(it) } ?: flowOf(emptyList())
    fun addMember(name:String){ val room=selectedRoomId?:return; if(name.isBlank()) return; viewModelScope.launch { if(dao.duplicate(room,name.trim())==0) dao.addMember(Member(roomId=room,name=name.trim())) } }
    fun deleteMember(member: Member){ viewModelScope.launch { dao.delMember(member) } }
    fun addExpense(title:String, amount:String, payerId:Long?, split:List<Long>, category:String){
        val room=selectedRoomId?:return
        val amt=amount.toDoubleOrNull()?:return
        if(title.isBlank() || amt<=0 || payerId==null || split.isEmpty()) return
        viewModelScope.launch { dao.addExpense(Expense(roomId = room,title=title.trim(),amount=amt,payerId=payerId,splitMemberIds=split.joinToString(","),category=category)) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(this, AppDb::class.java, "clearshare.db").build()
        val vm = ViewModelProvider(this, object: ViewModelProvider.Factory{ override fun <T : ViewModel> create(modelClass: Class<T>): T = MainVm(db.dao()) as T })[MainVm::class.java]
        setContent { App(vm) }
    }
}

@Composable
fun App(vm: MainVm) {
    val rooms by vm.rooms.collectAsState()
    val showCreate by vm.showCreateFirstRoom.collectAsState()
    LaunchedEffect(rooms) { if (rooms.isNotEmpty()) vm.pickLastRoom() }
    MaterialTheme {
        if (showCreate) FirstRoomScreen(vm) else HomeScreen(vm, rooms)
    }
}

@Composable
private fun FirstRoomScreen(vm: MainVm){
    var name by remember { mutableStateOf(TextFieldValue("")) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        Text("Create Your First Room", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Room Name") })
        Button(onClick = { vm.createRoom(name.text) }, modifier = Modifier.padding(top = 12.dp)) { Text("Create") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: MainVm, rooms: List<RoomGroup>) {
    val members by vm.membersFlow().collectAsState(initial = emptyList())
    val expenses by vm.expensesFlow().collectAsState(initial = emptyList())
    var member by remember { mutableStateOf(TextFieldValue("")) }
    var showExpense by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Clear Share") }, actions = {
            var exp by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { exp = true }) { Text(rooms.firstOrNull { it.id == vm.selectedRoomId }?.name ?: "Room") }
                DropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                    rooms.forEach { r -> DropdownMenuItem(text = { Text(r.name) }, onClick = { vm.selectedRoomId = r.id; exp = false }) }
                }
            }
        })
    }, floatingActionButton = { FloatingActionButton(onClick = { showExpense = true }) { Text("+") } }) { p ->
        Column(Modifier.padding(p).padding(16.dp)) {
            Text("Members (${members.size})")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = member, onValueChange = { member = it }, label = { Text("Member Name") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.addMember(member.text); member = TextFieldValue("") }) { Text("Add") }
            }
            LazyColumn(Modifier.height(140.dp)) { items(members) { m -> TextButton(onClick = { vm.deleteMember(m) }) { Text("Delete ${m.name}") } } }
            Text("Expenses")
            LazyColumn { items(expenses) { e -> Text("${e.title}: ${money(e.amount)}") } }
            val settlements = calculateSettlements(members, expenses)
            if (members.size > 1) {
                Text("Who Pays Whom")
                settlements.forEach { Text("${it.from} Needs to Pay ${money(it.amount)} to ${it.to} (Will Receive)") }
            } else {
                Text("Solo mode: personal analytics only.")
            }
        }
    }
    if (showExpense) ExpenseSheet(onDismiss = { showExpense = false }, members = members) { t,a,c,p,s -> vm.addExpense(t,a,p,s,c); showExpense = false }
}

data class Tx(val from:String, val to:String, val amount:Double)
private fun calculateSettlements(members: List<Member>, expenses: List<Expense>): List<Tx> {
    val bal = members.associate { it.id to 0.0 }.toMutableMap()
    expenses.forEach { e ->
        val split = e.splitMemberIds.split(",").mapNotNull { it.toLongOrNull() }
        if (split.isEmpty()) return@forEach
        val share = e.amount / split.size
        split.forEach { bal[it] = (bal[it] ?: 0.0) - share }
        bal[e.payerId] = (bal[e.payerId] ?: 0.0) + e.amount
    }
    val creditors = bal.filter { it.value > 0.0001 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
    val debtors = bal.filter { it.value < -0.0001 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
    val names = members.associate { it.id to it.name }
    val out = mutableListOf<Tx>()
    var i=0; var j=0
    while(i<debtors.size && j<creditors.size){
        val pay=minOf(debtors[i].second,creditors[j].second)
        out += Tx(names[debtors[i].first]?:"-", names[creditors[j].first]?:"-", pay)
        debtors[i]=debtors[i].first to (debtors[i].second-pay)
        creditors[j]=creditors[j].first to (creditors[j].second-pay)
        if (debtors[i].second < 0.0001) i++
        if (creditors[j].second < 0.0001) j++
    }
    return out
}

@Composable
private fun ExpenseSheet(onDismiss:()->Unit, members: List<Member>, onSave:(String,String,String,Long?,List<Long>)->Unit){
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var payer by remember { mutableStateOf<Long?>(null) }
    val split = remember { mutableStateListOf<Long>() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add Expense") }, text = {
        Column {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (required)") })
            OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount ₹ (required)") })
            OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") })
            Text("Date: ${LocalDate.now()}")
            Text("Payer")
            members.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = payer==m.id, onClick = { payer=m.id }); Text(m.name) } }
            Text("Split Members")
            members.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = split.contains(m.id), onCheckedChange = { if (it) split.add(m.id) else split.remove(m.id) }); Text(m.name) } }
        }
    }, confirmButton = { Button(onClick = { onSave(title, amount, category, payer, split) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun money(v: Double): String = "₹" + DecimalFormat("0.00").format(v)
