const storage = window.LaStorage;
let state = storage.getState();
let visibleMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
let editMode = "normal";
let pendingPattern = "";
let activeDateKey = "";
let dragStartX = null;

const $ = id => document.getElementById(id);
const calendar = $("calendar");

function syncState() { state = storage.getState(); }
window.addEventListener("las-state-changed", () => { syncState(); render(); });

function key(date) {
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,"0")}-${String(date.getDate()).padStart(2,"0")}`;
}
function dateFromKey(value) {
  const [y,m,d] = value.split("-").map(Number);
  return new Date(y,m-1,d);
}
function day(date) { return new Date(date.getFullYear(),date.getMonth(),date.getDate()); }
function diff(a,b) { return Math.round((day(b)-day(a))/86400000); }
function mod(a,b) { return ((a%b)+b)%b; }

function shiftFor(date) { return state.shifts[key(date)] || null; }
function picks(s={}) {
  return ["cancel","accept","returns","issue","reject","payment","repack"]
    .reduce((sum,k)=>sum+Number(s[k]||0),0);
}
function net(s={}) {
  const x = state.settings.basePickPrice * (
    Number(s.cancel||0)*.6 +
    Number(s.accept||0)*.8 +
    Number(s.returns||0)*.9 +
    Number(s.issue||0)*1.1 +
    Number(s.reject||0)*1.1 +
    Number(s.payment||0)*1.1 +
    Number(s.repack||0)*1.3
  );
  const shiftPay = picks(s)>0
    ? state.settings.shiftHours*state.settings.hourlyRate : 0;
  return (x+shiftPay)*(1-state.settings.taxPercent/100);
}
function scheduled(date) {
  const {pattern,anchorDate}=state.schedule;
  if(!pattern||!anchorDate)return false;
  const delta=diff(dateFromKey(anchorDate),date);
  if(pattern==="5/2")return mod(delta,7)<5;
  const work=pattern==="2/2"?2:pattern==="3/3"?3:0;
  return work>0 && mod(delta,work*2)<work;
}
function workday(date) {
  const s=shiftFor(date);
  if(s?.override===1||picks(s)>0)return true;
  if(s?.override===0)return false;
  return scheduled(date);
}
function color(date) {
  const first=date.getDate()<=15;
  const even=(date.getMonth()+1)%2===0;
  return even?(first?"#4270a8":"#cd6937"):(first?"#4c8c54":"#7f5696");
}
function render() {
  $("monthTitle").textContent=visibleMonth.toLocaleDateString("ru-RU",{month:"long",year:"numeric"});
  $("modeHint").textContent=editMode==="add"?"Нажмите дату, чтобы добавить смену":
    editMode==="remove"?"Нажмите дату, чтобы убрать смену":
    state.schedule.pattern?`График ${state.schedule.pattern}`:"График не выбран";
  renderCalendar();
  renderPayments();
}
function renderCalendar() {
  calendar.innerHTML="";
  ["Пн","Вт","Ср","Чт","Пт","Сб","Вс"].forEach(v=>{
    const e=document.createElement("div"); e.className="weekday"; e.textContent=v; calendar.append(e);
  });
  const first=new Date(visibleMonth.getFullYear(),visibleMonth.getMonth(),1);
  const offset=(first.getDay()+6)%7;
  const total=new Date(visibleMonth.getFullYear(),visibleMonth.getMonth()+1,0).getDate();
  for(let i=0;i<42;i++){
    const n=i-offset+1, cell=document.createElement("button");
    cell.className="day";
    if(n<1||n>total){cell.disabled=true;cell.style.visibility="hidden";calendar.append(cell);continue;}
    const date=new Date(visibleMonth.getFullYear(),visibleMonth.getMonth(),n);
    const s=shiftFor(date), amount=s?net(s):0;
    cell.textContent=`${n}${amount>0?`\n${Math.round(amount)}₽`:""}`;
    if(workday(date)){cell.classList.add("work");cell.style.background=color(date);}
    if(amount>=5000)cell.classList.add("good");
    if(key(date)===key(new Date()))cell.classList.add("today");
    cell.onclick=()=>dateClicked(date);
    calendar.append(cell);
  }
}
async function dateClicked(date) {
  const k=key(date);
  if(editMode==="add"){await storage.update(s=>{s.shifts[k]={...(s.shifts[k]||{}),override:1};return s;});editMode="normal";return;}
  if(editMode==="remove"){await storage.update(s=>{s.shifts[k]={...(s.shifts[k]||{}),override:0};return s;});editMode="normal";return;}
  if(!workday(date)){alert("Сначала добавьте смену через меню «График».");return;}
  activeDateKey=k;
  const s=state.shifts[k]||{override:1};
  $("shiftDateTitle").textContent=date.toLocaleDateString("ru-RU",{day:"numeric",month:"long",year:"numeric"});
  [["cancelCount","cancel"],["acceptCount","accept"],["returnCount","returns"],
   ["issueCount","issue"],["rejectCount","reject"],["paymentCount","payment"],["repackCount","repack"]]
   .forEach(([id,name])=>$(id).value=s[name]||"");
  preview();
  $("shiftDialog").showModal();
}
function dialogShift() {
  return {
    ...(state.shifts[activeDateKey]||{}), override:1,
    cancel:+$("cancelCount").value||0, accept:+$("acceptCount").value||0,
    returns:+$("returnCount").value||0, issue:+$("issueCount").value||0,
    reject:+$("rejectCount").value||0, payment:+$("paymentCount").value||0,
    repack:+$("repackCount").value||0
  };
}
function preview(){$("shiftPreview").textContent=`Чистыми: ${Math.round(net(dialogShift()))} ₽`;}
function periods(start,count) {
  const result=[];
  for(let i=0;i<count;i++){
    const d=new Date(start.getFullYear(),start.getMonth()+i,1), y=d.getFullYear(),m=d.getMonth();
    result.push({pay:new Date(y,m,25),from:new Date(y,m,1),to:new Date(y,m,15),color:color(new Date(y,m,1))});
    result.push({pay:new Date(y,m+1,10),from:new Date(y,m,16),to:new Date(y,m+1,0),color:color(new Date(y,m,16))});
  }
  return result.sort((a,b)=>a.pay-b.pay);
}
function periodTotal(p) {
  return Object.entries(state.shifts).reduce((sum,[k,s])=>{
    const d=dateFromKey(k); return d>=day(p.from)&&d<=day(p.to)?sum+net(s):sum;
  },0);
}
function fmt(d){return d.toLocaleDateString("ru-RU",{day:"numeric",month:"long",year:"numeric"});}
function short(d){return d.toLocaleDateString("ru-RU",{day:"numeric",month:"short"});}
function upcoming() {
  const now=day(new Date());
  return periods(new Date(now.getFullYear(),now.getMonth()-2,1),8).filter(p=>day(p.pay)>=now).slice(0,2);
}
function renderPayments() {
  $("payments").innerHTML="";
  upcoming().forEach((p,i)=>{
    const e=document.createElement("button");e.className="payment-card";e.style.setProperty("--accent",p.color);
    e.innerHTML=`<span>${i?"Следующая":"Ближайшая"}</span><b>${fmt(p.pay)}</b><strong>${Math.round(periodTotal(p))} ₽</strong><small>${short(p.from)}–${short(p.to)}</small>`;
    e.onclick=showPayments;$("payments").append(e);
  });
}
function showPayments() {
  const now=new Date();
  $("paymentsList").innerHTML=periods(new Date(now.getFullYear(),now.getMonth()-12,1),25).map(p=>
    `<div class="payment-card" style="--accent:${p.color};margin-bottom:8px"><b>${fmt(p.pay)}</b><strong>${Math.round(periodTotal(p))} ₽</strong><small>${short(p.from)}–${short(p.to)}</small></div>`
  ).join("");
  $("menuDialog").close();$("paymentsDialog").showModal();
}
function move(delta){visibleMonth=new Date(visibleMonth.getFullYear(),visibleMonth.getMonth()+delta,1);render();}

$("prevMonth").onclick=()=>move(-1);$("nextMonth").onclick=()=>move(1);
$("todayButton").onclick=()=>{visibleMonth=new Date(new Date().getFullYear(),new Date().getMonth(),1);render();};
$("menuButton").onclick=async()=>{await refreshStorageStatus();$("menuDialog").showModal();};
$("scheduleButton").onclick=()=>$("scheduleDialog").showModal();
$("allPaymentsButton").onclick=showPayments;
document.querySelectorAll("[data-pattern]").forEach(b=>b.onclick=()=>{pendingPattern=b.dataset.pattern;$("anchorDate").value=key(new Date());$("scheduleDialog").close();$("anchorDialog").showModal();});
$("savePatternButton").onclick=async()=>{if(!$("anchorDate").value)return;await storage.update(s=>{s.schedule={pattern:pendingPattern,anchorDate:$("anchorDate").value};return s;});editMode="normal";$("anchorDialog").close();};
$("addShiftMode").onclick=()=>{editMode="add";$("scheduleDialog").close();render();};
$("removeShiftMode").onclick=()=>{editMode="remove";$("scheduleDialog").close();render();};
["cancelCount","acceptCount","returnCount","issueCount","rejectCount","paymentCount","repackCount"].forEach(id=>$(id).oninput=preview);
$("saveShiftButton").onclick=async()=>{const value=dialogShift();await storage.update(s=>{s.shifts[activeDateKey]=value;return s;});$("shiftDialog").close();};
$("settingsButton").onclick=()=>{["basePickPrice","shiftHours","hourlyRate","taxPercent"].forEach(id=>$(id).value=state.settings[id]);$("menuDialog").close();$("settingsDialog").showModal();};
$("saveSettingsButton").onclick=async()=>{await storage.update(s=>{s.settings={basePickPrice:+$("basePickPrice").value||6.1,shiftHours:+$("shiftHours").value||10.75,hourlyRate:+$("hourlyRate").value||147,taxPercent:+$("taxPercent").value||13};return s;});$("settingsDialog").close();};
$("exportButton").onclick=()=>storage.exportJson();
$("importInput").onchange=async e=>{try{if(e.target.files[0])await storage.importJson(e.target.files[0]);}catch(err){alert(err.message);}finally{e.target.value="";$("menuDialog").close();}};
$("enableAutoBackupButton").onclick=async()=>{try{await storage.enableAutoBackup();await refreshStorageStatus();}catch(err){alert(err.message);}};
$("disableAutoBackupButton").onclick=async()=>{await storage.disableAutoBackup();await refreshStorageStatus();};
async function refreshStorageStatus(){
  const s=await storage.getAutoBackupStatus();
  $("storageStatus").textContent=!s.supported?"Автосохранение в файл не поддерживается этим браузером":
    s.configured?`Автосохранение: ${s.fileName}`:"Автосохранение в файл выключено";
  $("enableAutoBackupButton").hidden=!s.supported;
  $("disableAutoBackupButton").hidden=!s.configured;
}
calendar.onpointerdown=e=>dragStartX=e.clientX;
calendar.onpointerup=e=>{if(dragStartX===null)return;const dx=e.clientX-dragStartX;dragStartX=null;if(Math.abs(dx)>55)move(dx<0?1:-1);};
render();
