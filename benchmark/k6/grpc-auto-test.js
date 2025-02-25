import grpc from 'k6/net/grpc';
import {check, sleep} from 'k6';

const SETUP = __ENV.SETUP;
const config = JSON.parse(open('./setup.json'))[SETUP];

const client = new grpc.Client();
client.load(['.'], 'MessageController.proto');

export let options = config.options;

const serverAddress = `${config.host}:6565`;
const serviceName = 'com.zero_touch.benchmark.controllers.MessageControllerGen.MessageController';
const methodName = 'getMessage';
const fullMethod = `${serviceName}/${methodName}`;

export default function () {
    client.connect(serverAddress, {plaintext: true});

    let multiplier;
    if (Array.isArray(config.multiplier)) {
        multiplier = config.multiplier[ Math.floor(Math.random() * config.multiplier.length) ];
    } else { multiplier = config.multiplier; }
    const request = {
        message: {
            multiplier: multiplier,
            text: config.text,
        },
    };

    const response = client.invoke(fullMethod, request);
    check(response, {
        'status is OK': (r) => r && r.status === grpc.StatusOK,
    });
    client.close();
    sleep(config.sleep);
}
