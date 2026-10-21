#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const fixtureRoot = path.resolve(process.env.SECTION17_FIXTURE_DIR || path.join(projectRoot, "data/test-fixtures/full-system-20260805"));
const fail = message => { throw new Error(message); };
const read = name => fs.readFileSync(path.join(fixtureRoot, name), "utf8").replace(/^\uFEFF/, "");
const sha256 = file => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");

function csvRows(text) {
  const rows=[]; let row=[],field="",quoted=false;
  for(let i=0;i<text.length;i++){
    const c=text[i];
    if(quoted){if(c==='"'&&text[i+1]==='"'){field+='"';i++;}else if(c==='"')quoted=false;else field+=c;}
    else if(c==='"')quoted=true;else if(c===','){row.push(field);field="";}else if(c==='\n'){row.push(field.replace(/\r$/,""));rows.push(row);row=[];field="";}else field+=c;
  }
  if(field.length||row.length){row.push(field);rows.push(row);} return rows;
}

const manifest=JSON.parse(read("manifest.json"));
if(!manifest.synthetic_data_only||manifest.contains_real_personal_data)fail("fixture manifest does not assert synthetic-only data");
for(const [name,expected] of Object.entries(manifest.files_sha256)){
  const file=path.join(fixtureRoot,name); if(!fs.existsSync(file))fail(`manifest file missing: ${name}`);
  const actual=sha256(file); if(actual!==expected)fail(`hash mismatch: ${name}`);
}

const sdk=read("sdk-feedback-360.ndjson").trim().split(/\r?\n/).map((line,index)=>{try{return JSON.parse(line);}catch{fail(`invalid SDK NDJSON line ${index+1}`);}});
const expectedRows=csvRows(read("expected-results.csv"));
const clusters=csvRows(read("cross-channel-clusters.csv"));
const maskingCases=csvRows(read("privacy-masking-cases.csv"));
const registry=JSON.parse(read("privacy-fixture-registry.json"));
const expectedData=expectedRows.slice(1);
const byChannel=Object.fromEntries(["SDK","JIRA","QUALITY","REDBLACK"].map(channel=>[channel,expectedData.filter(row=>row[0]===channel).length]));
const required={SDK:360,JIRA:320,QUALITY:260,REDBLACK:240};
for(const [channel,count] of Object.entries(required))if(byChannel[channel]!==count)fail(`${channel} expected-results count ${byChannel[channel]} != ${count}`);
if(sdk.length!==manifest.counts.sdk_feedback)fail("SDK row count mismatch");
if(clusters.length-1!==manifest.counts.cross_channel_clusters)fail("cross-channel cluster count mismatch");
if(maskingCases.length-1!==manifest.counts.masking_cases)fail("masking case count mismatch");
if(registry.entries.length!==manifest.counts.registered_privacy_fixtures)fail("privacy fixture registry count mismatch");
for(const entry of registry.entries){
  if(!entry.synthetic||entry.purpose!=="MASKING_RULE_VALIDATION_ONLY"||!entry.prohibited_for_demo_identity)fail(`unsafe registry declaration: ${entry.fixture_id}`);
  if(crypto.createHash("sha256").update(entry.exact_value).digest("hex")!==entry.sha256)fail(`privacy fixture hash mismatch: ${entry.fixture_id}`);
}

const xlsxSpecs={"jira-issues-320.xlsx":320,"quality-platform-a-140.xlsx":140,"quality-platform-b-120.xlsx":120,"redblack-a-130.xlsx":130,"redblack-b-110.xlsx":110};
const searchable=[read("sdk-feedback-360.ndjson"),read("privacy-masking-cases.csv")];
for(const [name,expectedCount] of Object.entries(xlsxSpecs)){
  const file=path.join(fixtureRoot,name);
  execFileSync("unzip",["-t",file],{stdio:"ignore"});
  const sheet=execFileSync("unzip",["-p",file,"xl/worksheets/sheet1.xml"],{encoding:"utf8",maxBuffer:64*1024*1024});
  const rowCount=(sheet.match(/<(?:[A-Za-z0-9_]+:)?row\b/g)||[]).length-1; if(rowCount!==expectedCount)fail(`${name} row count ${rowCount} != ${expectedCount}`);
  let strings="";try{strings=execFileSync("unzip",["-p",file,"xl/sharedStrings.xml"],{encoding:"utf8",maxBuffer:64*1024*1024});}catch{}
  const stringCells=[...`${sheet}\n${strings}`.matchAll(/<(?:[A-Za-z0-9_]+:)?t(?:\s[^>]*)?>([\s\S]*?)<\/(?:[A-Za-z0-9_]+:)?t>/g)].map(match=>match[1]);
  searchable.push(stringCells.join("\n"));
}

const combined=searchable.join("\n");
const sensitivePatterns=[/1[3-9][0-9]{9}/g,/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g,/[1-9][0-9]{5}(?:18|19|20)[0-9]{2}[01][0-9][0-3][0-9][0-9]{3}[0-9Xx]/g];
const registeredValues=registry.entries.map(entry=>entry.exact_value);
for(const pattern of sensitivePatterns){
  for(const match of combined.match(pattern)||[]){
    if(!registeredValues.some(value=>value.includes(match)))fail(`unregistered sensitive-shaped fixture: ${match}`);
  }
}

const uniqueDimensions={
  applications:new Set(sdk.map(row=>row.package_name)).size,
  modules:new Set(sdk.map(row=>row.function_module)).size,
  versions:new Set(sdk.map(row=>row.app_version)).size,
  deviceModels:new Set(sdk.map(row=>row.device_model)).size
};
if(uniqueDimensions.applications<2||uniqueDimensions.modules<2||uniqueDimensions.versions<2||uniqueDimensions.deviceModels<2)fail("insufficient SDK dimension variety");
const outcomes=new Set(expectedData.map(row=>row[2]));
for(const requiredOutcome of ["MISSING_REQUIRED_FIELD","FORMAT_ERROR","DUPLICATE_RECORD","INVALID_DATE","INVALID_ENUM","MASKING_REVIEW"]){if(!outcomes.has(requiredOutcome))fail(`missing negative fixture: ${requiredOutcome}`);}

console.log(JSON.stringify({status:"PASS",fixtureRoot,totalRecords:expectedData.length,channels:byChannel,xlsxFiles:Object.keys(xlsxSpecs).length,crossChannelClusters:clusters.length-1,privacyFixtures:registry.entries.length,uniqueDimensions,hashesVerified:Object.keys(manifest.files_sha256).length},null,2));
