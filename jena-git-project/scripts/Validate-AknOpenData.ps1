[CmdletBinding()]
param(
  [Parameter(Mandatory,Position=0)][string]$Path,
  [string]$OutputPath=(Join-Path (Get-Location) 'akn-validation-report.json')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$akn='http://docs.oasis-open.org/legaldocml/ns/akn/3.0'

function Load-Xml([string]$file) {
  $s=[Xml.XmlReaderSettings]::new(); $s.DtdProcessing='Prohibit'; $s.XmlResolver=$null
  $r=[Xml.XmlReader]::Create($file,$s)
  try { $d=[Xml.XmlDocument]::new(); $d.XmlResolver=$null; $d.Load($r); $d } finally { $r.Dispose() }
}
function Value($d,$n,[string]$xpath,[string]$attr='') {
  $x=$d.SelectSingleNode($xpath,$n); if($null -eq $x){return $null}
  $v=if($attr){$x.GetAttribute($attr)}else{$x.InnerText}
  if([string]::IsNullOrWhiteSpace($v)){return $null}; ($v-replace '\s+',' ').Trim()
}
function Target([string]$href) {
  if(!$href){return $null}; ($href.Split('#')[0] -replace '/!main$','')
}
function Issue($list,$severity,$code,$message,$location='') {
  $list.Add([pscustomobject]@{severity=$severity;code=$code;message=$message;location=$location})
}
function Check-File($file) {
  $issues=[Collections.Generic.List[object]]::new()
  $rels=[Collections.Generic.List[object]]::new()
  $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
  try {$d=Load-Xml $file.FullName} catch {
    Issue $issues ERROR XML_INVALID $_.Exception.Message
    return [pscustomobject]@{file=$file.FullName;sha256=$hash;status='REJECTED';queryable=$false;act=$null;counts=$null;relations=@();issues=@($issues)}
  }
  $n=[Xml.XmlNamespaceManager]::new($d.NameTable); $n.AddNamespace('akn',$akn); $n.AddNamespace('rdf','http://www.w3.org/1999/02/22-rdf-syntax-ns#')
  if($d.DocumentElement.LocalName-ne'akomaNtoso'-or$d.DocumentElement.NamespaceURI-ne$akn){Issue $issues ERROR AKN_NAMESPACE_INVALID 'Root is not Akoma Ntoso 3.0.'}
  $a=[ordered]@{
    type=Value $d $n '//akn:docType'; number=Value $d $n '//akn:docNumber'
    documentDate=Value $d $n '//akn:docDate' date; title=Value $d $n '//akn:docTitle'
    publicationDate=Value $d $n '//akn:publication' date; gazetteNumber=Value $d $n '//akn:publication' number
    localId=Value $d $n "//*[local-name()='span'][@property='eli:id_local']" content
    workUri=Value $d $n '//akn:FRBRWork/akn:FRBRuri' value
    expressionUri=Value $d $n '//akn:FRBRExpression/akn:FRBRuri' value
    expressionDate=Value $d $n '//akn:FRBRExpression/akn:FRBRdate' date
    manifestationUri=Value $d $n '//akn:FRBRManifestation/akn:FRBRuri' value
    eliAlias=Value $d $n "//akn:FRBRWork/akn:FRBRalias[@name='eli']" value
    urnAlias=Value $d $n "//akn:FRBRWork/akn:FRBRalias[@name='urn:nir']" value
  }
  foreach($k in 'type','number','documentDate','title','publicationDate','workUri','expressionUri','manifestationUri'){
    if(!$a[$k]){Issue $issues ERROR ('MISSING_'+$k.ToUpper()) "Required field $k is missing."}
  }
  foreach($k in 'documentDate','publicationDate','expressionDate'){
    if($a[$k]){$v=[datetime]::MinValue;if(-not[datetime]::TryParseExact($a[$k],'yyyy-MM-dd',$null,0,[ref]$v)){Issue $issues ERROR DATE_INVALID "$k is invalid: $($a[$k])"}}
  }
  if($a.urnAlias){Issue $issues INFO URN_ALIAS_PRESENT 'Keep the URN only as source metadata, never as an RDF subject.'}
  $types=@($d.SelectNodes("//*[local-name()='Description']/*[local-name()='type']/@rdf:resource",$n)|% Value)
  if($types-contains'eli:format'){Issue $issues WARNING ELI_FORMAT_CLASS_CASE 'Normalize RDF class eli:format to eli:Format.'}
  $refs=@($d.SelectNodes('//akn:ref',$n))
  foreach($r in $refs){$h=$r.GetAttribute('href');if(!$h){Issue $issues WARNING REFERENCE_TARGET_MISSING 'Reference has no href.'}elseif($h-match'/act/[^/]*/{2,}'){Issue $issues WARNING REFERENCE_TARGET_MALFORMED "Malformed target: $h"}}
  $source=if($a.eliAlias){$a.eliAlias}else{$a.workUri}
  foreach($article in @($d.SelectNodes('//akn:article',$n))){
    $h=$article.SelectSingleNode('./akn:heading',$n);$heading=if($h){($h.InnerText-replace'\s+',' ').Trim()}else{''}
    if($heading-notmatch'(?i)modific'){continue}
    $firstTargetRef=$article.SelectSingleNode('.//akn:ref[not(ancestor::akn:authorialNote)][1]',$n)
    $targets=if($firstTargetRef){@(Target $firstTargetRef.GetAttribute('href')|Where-Object{$_ -match'^/akn/it/act/' -and $_ -notmatch'/act/[^/]*/{2,}'})}else{@()}
    foreach($t in $targets){$rels.Add([pscustomobject]@{status='VERIFIED';type='AMENDS';source=$source;target=$t;evidence=$heading;location=$article.GetAttribute('eId');ruleId='AMENDS_EXPLICIT_V1'})}
    if(!$targets){Issue $issues WARNING RELATION_TARGET_MISSING 'Modification language found without a valid target.' $heading}
  }
  $rels=@($rels|sort type,target -Unique);$errors=@($issues|Where-Object severity -eq 'ERROR').Count;$warnings=@($issues|Where-Object severity -eq 'WARNING').Count
  $ok=$errors-eq0;$status=if(!$ok){'REJECTED'}elseif($warnings){'VALID_WITH_WARNINGS'}else{'VALID'}
  [pscustomobject]@{file=$file.FullName;sha256=$hash;status=$status;queryable=$ok;act=[pscustomobject]$a;counts=[pscustomobject]@{references=$refs.Count;uniqueReferenceTargets=@($refs|%{Target $_.GetAttribute('href')}|?{$_}|sort -Unique).Count;verifiedRelations=$rels.Count;warnings=$warnings;errors=$errors};relations=$rels;issues=@($issues)}
}

$item=Get-Item -LiteralPath (Resolve-Path -LiteralPath $Path)
$files=if($item.PSIsContainer){@(Get-ChildItem -LiteralPath $item.FullName -Filter *.xml -File -Recurse)}else{@($item)}
if(!$files){throw "No XML files found at $Path"}
$results=@($files|%{Check-File $_})
$summary=[pscustomobject]@{generatedAt=[datetime]::UtcNow.ToString('o');input=$item.FullName;filesChecked=$results.Count;queryable=@($results|Where-Object queryable).Count;rejected=@($results|Where-Object{-not$_.queryable}).Count;verifiedRelations=($results|%{$_.counts.verifiedRelations}|measure -Sum).Sum;results=$results}
$summary|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Host "`nAkoma Ntoso validation: $($summary.filesChecked) checked, $($summary.queryable) queryable, $($summary.rejected) rejected"
foreach($r in $results){Write-Host "[$($r.status)] $([IO.Path]::GetFileName($r.file))";if($r.act){Write-Host "  $($r.act.type) $($r.act.number), $($r.act.documentDate); refs=$($r.counts.references), relations=$($r.counts.verifiedRelations)"};foreach($x in $r.relations){Write-Host "  VERIFIED $($x.type) -> $($x.target)"};foreach($i in $r.issues|Where-Object severity -ne 'INFO'){Write-Host "  $($i.severity) $($i.code): $($i.message)"}}
Write-Host "`nJSON report: $OutputPath"
if($summary.rejected){exit 1}
