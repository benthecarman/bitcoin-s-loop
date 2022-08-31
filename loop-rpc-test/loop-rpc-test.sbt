Test / test := (Test / test dependsOn {
  Projects.loopRpc / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  Projects.loopRpc / TaskKeys.downloadLnd
}).value

Test / test := (Test / test dependsOn {
  Projects.loopRpc / TaskKeys.downloadLoop
}).value
