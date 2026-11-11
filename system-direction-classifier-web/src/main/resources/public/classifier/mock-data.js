/* Local demonstration fixtures. No algorithm or model requests are made. */
(() => {
'use strict';
const seedData=()=>({
 tasks:[{id:'t1',name:'论文摘要批量识别',version:'v2.3',kbVersion:'',file:'abstracts_120.csv',date:'2026-11-10 15:42',total:120,success:68,failed:2,pending:50,status:'处理中',rows:[]},{id:'t2',name:'国际会议论文识别',version:'v1.2',kbVersion:'',file:'conference_500.csv',date:'2026-11-09 18:30',total:500,success:500,failed:0,pending:0,status:'已完成',rows:[]},{id:'t3',name:'学科交叉案例集识别',version:'v2.2',kbVersion:'',file:'case_study_200.csv',date:'2026-11-06 10:05',total:200,success:177,failed:23,pending:0,status:'需检查',rows:[]}]
});

window.ClassifierMockData = Object.freeze({seedData});
})();
